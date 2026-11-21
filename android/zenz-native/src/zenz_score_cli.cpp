// Host evaluation tool: reads `key<TAB>katakana reading<TAB>surface` rows (UTF-8) and writes
// `key<TAB>logprob` rows. Consecutive rows with the same reading share one prompt decode.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include "zenz_scorer.h"

int main(int argc, char ** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: zenz-score <model.gguf> <in.tsv> <out.tsv> [threads]\n");
        return 2;
    }
    const int threads = argc > 4 ? std::atoi(argv[4]) : 4;
    auto t0 = std::chrono::steady_clock::now();
    ZenzScorer * scorer = ZenzScorer::open(argv[1], threads);
    if (!scorer) {
        std::fprintf(stderr, "model load failed\n");
        return 1;
    }
    auto t1 = std::chrono::steady_clock::now();
    std::ifstream in(argv[2], std::ios::binary);
    std::ofstream out(argv[3], std::ios::binary);
    struct Row { std::string key, reading, surface; };
    std::vector<Row> rows;
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const size_t a = line.find('\t');
        const size_t b = a == std::string::npos ? a : line.find('\t', a + 1);
        if (b == std::string::npos) continue;
        rows.push_back({line.substr(0, a), line.substr(a + 1, b - a - 1), line.substr(b + 1)});
    }
    size_t groups = 0;
    std::vector<long long> group_us;
    for (size_t i = 0; i < rows.size();) {
        size_t j = i;
        std::vector<std::string> candidates;
        while (j < rows.size() && rows[j].reading == rows[i].reading) candidates.push_back(rows[j++].surface);
        std::vector<float> scores;
        auto g0 = std::chrono::steady_clock::now();
        const bool ok = scorer->score(ZenzScorer::prompt(rows[i].reading), candidates, scores);
        group_us.push_back(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - g0).count());
        for (size_t k = i; k < j; k++) out << rows[k].key << '\t' << (ok ? scores[k - i] : -1e9f) << '\n';
        groups++;
        i = j;
    }
    auto t2 = std::chrono::steady_clock::now();
    std::sort(group_us.begin(), group_us.end());
    auto pct = [&](double p) { return group_us.empty() ? 0LL : group_us[std::min(group_us.size() - 1, size_t(p * group_us.size()))]; };
    std::fprintf(stderr, "loadMs=%lld rows=%zu groups=%zu totalMs=%lld groupP50Us=%lld groupP95Us=%lld groupMaxUs=%lld\n",
        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count(), rows.size(), groups,
        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count(), pct(0.5), pct(0.95), pct(1.0));
    delete scorer;
    return 0;
}
