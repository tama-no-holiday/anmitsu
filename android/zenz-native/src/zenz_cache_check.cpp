// Host regression for the scorer's prompt cache: the same (context, reading, candidates) must score the same whatever ran
// before. Each case is scored after a fresh open, again, after an unrelated prompt, after a prompt sharing a prefix and
// with the candidates reversed, and compared with a reference on an empty context that decodes in the scorer's blocks of
// 8. The whole sequence in one batch is shown for information only: it differs by more than rounding (zenz_scorer.cpp,
// decode_block). All inputs are synthetic and built into this file.
// Exit 0 when every scorer state is within the tolerance, 3 otherwise.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "llama.h"
#include "zenz_scorer.h"

namespace {

struct Case {
    std::string context, reading;
    std::vector<std::string> candidates;
};

const std::vector<Case> kCases = {
    {"", "ホシイ", {"欲しい", "ほしい", "星居", "保志井"}},
    {"今日は天気がいいです。", "ホシイ", {"ほしい", "欲しい", "星居"}},
    {"ありがとうございました。", "ホシイ", {"ほしい", "欲しい", "星居"}},
    {"私は歯医者です。", "シカイヲツトメマス", {"司会を務めます", "視界を努めます", "歯科医を務めます", "死界を務めます"}},
    {"私はアナウンサーです。", "シカイヲツトメマス", {"司会を務めます", "視界を努めます", "歯科医を務めます"}},
    {"", "キョウハイイテンキデスネ", {"今日はいい天気ですね", "今日は良い天気ですね", "京はいい天気ですね"}},
    {"よろしくお願いします。", "アシタアメガフル", {"明日雨が降る", "明日飴が降る", "足た雨が降る"}},
    {"来月の同窓会の店を探しています。", "カンジヲヒキウケマシタ", {"感じを引き受けました", "幹事を引き受けました", "漢字を引き受けました"}},
    {"", "ア", {"あ", "亜", "阿"}},
    {"冷蔵庫に牛乳が残っています。", "キシャニナリマシタ", {"記者になりました", "汽車になりました", "貴社になりました"}},
};

constexpr int kContext = 512;

std::string repeated(const std::string & text, int times) {
    std::string out;
    for (int i = 0; i < times; i++) out += text;
    return out;
}

void log_softmax(const float * logits, int n, std::vector<double> & out) {
    out.resize(size_t(n));
    double max = logits[0];
    for (int i = 1; i < n; i++) max = std::max(max, double(logits[i]));
    double sum = 0.0;
    for (int i = 0; i < n; i++) sum += std::exp(double(logits[i]) - max);
    const double lse = max + std::log(sum);
    for (int i = 0; i < n; i++) out[size_t(i)] = double(logits[i]) - lse;
}

// Same load settings as ZenzScorer::open; a separate context, so nothing is shared with the scorer under test.
struct Reference {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_vocab = 0;
    int end = -1;

    bool open(const char * path, int threads) {
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        static llama_model_kv_override overrides[2] = {};
        overrides[0].tag = LLAMA_KV_OVERRIDE_TYPE_STR;
        std::snprintf(overrides[0].key, sizeof(overrides[0].key), "%s", "tokenizer.ggml.pre");
        std::snprintf(overrides[0].val_str, sizeof(overrides[0].val_str), "%s", "llama3");
        mp.kv_overrides = overrides;
        model = llama_model_load_from_file(path, mp);
        if (!model) return false;
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = cp.n_batch = cp.n_ubatch = kContext;
        cp.n_seq_max = 1;
        cp.n_threads = cp.n_threads_batch = threads;
        cp.no_perf = true;
        ctx = llama_init_from_model(model, cp);
        if (!ctx) return false;
        const llama_vocab * vocab = llama_model_get_vocab(model);
        n_vocab = llama_vocab_n_tokens(vocab);
        end = llama_vocab_eos(vocab);
        llama_token t = -1;
        if (llama_tokenize(vocab, "</s>", 4, &t, 1, false, true) == 1) end = t;
        return true;
    }

    // log P(target tokens | prompt tokens), per target token. step is how many tokens each llama_decode takes; prompt
    // and target start a block each, as in the scorer, when blocks is set, and otherwise run on as one sequence.
    bool score(const std::vector<int> & prompt, const std::vector<int> & target, int step, bool blocks, std::vector<double> & out) {
        llama_memory_clear(llama_get_memory(ctx), true);
        std::vector<int> all(prompt);
        all.insert(all.end(), target.begin(), target.end() - 1);
        out.assign(target.size(), 0.0);
        std::vector<double> dist;
        for (size_t at = 0; at < all.size();) {
            size_t n = std::min(size_t(step), all.size() - at);
            if (blocks && at < prompt.size()) n = std::min(n, prompt.size() - at);
            llama_batch batch = llama_batch_init(int(n), 0, 1);
            for (size_t j = 0; j < n; j++) {
                batch.token[j] = all[at + j];
                batch.pos[j] = int(at + j);
                batch.n_seq_id[j] = 1;
                batch.seq_id[j][0] = 0;
                batch.logits[j] = at + j + 1 >= prompt.size() ? 1 : 0;
            }
            batch.n_tokens = int(n);
            const int rc = llama_decode(ctx, batch);
            if (rc == 0) {
                for (size_t j = 0; j < n; j++) {
                    if (at + j + 1 < prompt.size()) continue;
                    const size_t t = at + j + 1 - prompt.size();
                    log_softmax(llama_get_logits_ith(ctx, int(j)), n_vocab, dist);
                    out[t] = dist[size_t(target[t])];
                }
            }
            llama_batch_free(batch);
            if (rc != 0) return false;
            at += n;
        }
        return true;
    }

    ~Reference() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

double sum(const std::vector<double> & v) {
    double s = 0.0;
    for (double x : v) s += x;
    return s;
}

struct Stat {
    double worst = 0.0;
    int over = 0;
    int n = 0;
    std::string where;
};

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: zenz-cache-check <model.gguf> [threads] [tolerance nats] [--verbose]\n");
        return 2;
    }
    const int threads = argc > 2 ? std::atoi(argv[2]) : 4;
    const double tolerance = argc > 3 ? std::atof(argv[3]) : 1e-4;
    const bool verbose = argc > 4 && std::strcmp(argv[4], "--verbose") == 0;
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    ZenzScorer * scorer = ZenzScorer::open(argv[1], threads);
    Reference reference;
    if (!scorer || !reference.open(argv[1], threads)) {
        std::fprintf(stderr, "model load failed\n");
        return 1;
    }
    // Past 256 cells llama.cpp widens the KV window, and a single row takes another attention path; 40 emoji are 160 byte tokens.
    std::vector<Case> cases = kCases;
    cases.push_back({repeated("🍡", 40), repeated("アメ", 32), {repeated("雨", 32), repeated("飴", 32), repeated("あめ", 32)}});
    const std::string unrelated = ZenzScorer::prompt("カエルガナク", "犬が庭を走っています。");
    std::map<std::string, Stat> stats;
    const auto note = [&](const std::string & state, const std::string & where, double delta, double tol) {
        Stat & s = stats[state];
        s.n++;
        if (std::fabs(delta) > tol) s.over++;
        if (std::fabs(delta) > s.worst) {
            s.worst = std::fabs(delta);
            s.where = where;
        }
    };
    bool failed = false;
    int line = 0;
    for (const Case & c : cases) {
        line++;
        const std::string prompt = ZenzScorer::prompt(c.reading, c.context);
        const llama_vocab * vocab = llama_model_get_vocab(reference.model);
        const std::vector<int> prompt_tokens = scorer->tokenize(prompt, llama_vocab_get_add_bos(vocab));
        std::vector<std::vector<double>> whole(c.candidates.size()), blocks(c.candidates.size());
        for (size_t k = 0; k < c.candidates.size(); k++) {
            std::vector<int> target = scorer->tokenize(c.candidates[k], false);
            target.push_back(reference.end);
            if (!reference.score(prompt_tokens, target, kContext, false, whole[k]) || !reference.score(prompt_tokens, target, 8, true, blocks[k])) {
                std::fprintf(stderr, "reference decode failed\n");
                return 1;
            }
            const std::string where = "case " + std::to_string(line) + " candidate " + std::to_string(k);
            note("info: whole batch, not a failure", where, sum(whole[k]) - sum(blocks[k]), tolerance);
        }
        std::vector<float> first;
        const auto check = [&](const std::string & state, const std::vector<std::string> & candidates, const std::vector<float> & scores, bool reversed) {
            if (first.empty()) first = scores;
            for (size_t k = 0; k < candidates.size(); k++) {
                const size_t original = reversed ? candidates.size() - 1 - k : k;
                const double delta = double(scores[k]) - sum(blocks[original]);
                // Against the first state exactly: scores are floats from the same arithmetic, so history must not move a bit.
                if (scores[k] != first[original]) note("bits differ between states", state, double(scores[k]) - double(first[original]), 0.0);
                note(state, "case " + std::to_string(line) + " candidate " + std::to_string(original), delta, tolerance);
                if (verbose) std::printf("%s case=%d candidate=%zu scorer=%.5f reference=%.5f delta=%+.5f\n", state.c_str(), line, original, scores[k], sum(blocks[original]), delta);
            }
        };
        std::vector<float> scores;
        ZenzScorer * fresh = ZenzScorer::open(argv[1], threads);
        if (!fresh || !fresh->score(prompt, c.candidates, scores)) failed = true;
        else check("fresh open", c.candidates, scores, false);
        if (!fresh->score(prompt, c.candidates, scores)) failed = true;
        else check("warm identical", c.candidates, scores, false);
        delete fresh;
        std::vector<float> ignored;
        if (!scorer->score(unrelated, {"蛙が鳴く", "帰るがなく"}, ignored)) failed = true;
        if (!scorer->score(prompt, c.candidates, scores)) failed = true;
        else check("after unrelated prompt", c.candidates, scores, false);
        const std::string shorter = ZenzScorer::prompt(c.reading.substr(0, 3), c.context);
        if (c.reading.size() > 3) {
            if (!scorer->score(shorter, {c.candidates[0]}, ignored)) failed = true;
            if (!scorer->score(prompt, c.candidates, scores)) failed = true;
            else check("after prefix prompt", c.candidates, scores, false);
        }
        std::vector<std::string> backwards(c.candidates.rbegin(), c.candidates.rend());
        if (!scorer->score(prompt, backwards, scores)) failed = true;
        else check("reversed candidates", backwards, scores, true);
    }
    for (const auto & [state, s] : stats) {
        std::printf("%-32s n=%-4d over=%-4d worst=%.6f %s\n", state.c_str(), s.n, s.over, s.worst, s.where.c_str());
        if (s.over > 0 && state.rfind("info:", 0) != 0) failed = true;
    }
    delete scorer;
    std::printf("%s tolerance=%.6f threads=%d\n", failed ? "FAILED" : "PASSED", tolerance, threads);
    return failed ? 3 : 0;
}
