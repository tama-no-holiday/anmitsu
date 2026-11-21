#include "zenz_scorer.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "llama.h"

namespace {

void log_softmax(const float * logits, int n, std::vector<float> & out) {
    out.resize(n);
    float max = logits[0];
    for (int i = 1; i < n; i++) max = std::max(max, logits[i]);
    double sum = 0.0;
    for (int i = 0; i < n; i++) sum += std::exp(double(logits[i] - max));
    const float lse = max + float(std::log(sum));
    for (int i = 0; i < n; i++) out[i] = logits[i] - lse;
}

constexpr int kContext = 512;
constexpr int kMaxCandidateTokens = 160;  // room kept after the prompt for one candidate
constexpr int kBlock = 8;                 // tokens per llama_decode, see decode_block

}  // namespace

ZenzScorer * ZenzScorer::open(const std::string & model_path, int threads) {
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // zenz GGUFs declare the pre-tokenizer "gpt2-small-japanese-char", which upstream llama.cpp does not list.
    // The vocabulary is character level but its BPE merges do not cover every character (e.g. the U+EE00 prompt
    // marks), so a byte-level BPE would split them. A pre-tokenizer type with ignore_merges makes a word that is
    // in the vocabulary map straight to its token; tokenize() feeds one character at a time. The override is
    // applied at load time and the llama.cpp source stays unmodified.
    static llama_model_kv_override overrides[2] = {};
    overrides[0].tag = LLAMA_KV_OVERRIDE_TYPE_STR;
    std::snprintf(overrides[0].key, sizeof(overrides[0].key), "%s", "tokenizer.ggml.pre");
    std::snprintf(overrides[0].val_str, sizeof(overrides[0].val_str), "%s", "llama3");
    overrides[1].key[0] = '\0';
    mp.kv_overrides = overrides;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!model) return nullptr;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kContext;
    cp.n_batch = kContext;
    cp.n_ubatch = kContext;
    cp.n_seq_max = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return nullptr;
    }
    auto * s = new ZenzScorer();
    s->model_ = model;
    s->ctx_ = ctx;
    const llama_vocab * vocab = llama_model_get_vocab(model);
    s->n_vocab_ = llama_vocab_n_tokens(vocab);
    // zenz ends outputs with "</s>"; the GGUF's eos_token_id field points elsewhere, so look the token up.
    s->eos_ = llama_vocab_eos(vocab);
    llama_token end = -1;
    const std::string end_text = "</s>";
    if (llama_tokenize(vocab, end_text.data(), int(end_text.size()), &end, 1, false, true) == 1) s->eos_ = end;
    return s;
}

ZenzScorer::~ZenzScorer() {
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
}

std::string ZenzScorer::prompt(const std::string & katakana_reading, const std::string & left_context) {
    std::string p;
    if (!left_context.empty()) p += "\xEE\xB8\x82" + left_context;  // U+EE02
    p += "\xEE\xB8\x80";                                             // U+EE00
    p += katakana_reading;
    p += "\xEE\xB8\x81";                                             // U+EE01
    return p;
}

// One UTF-8 code point at a time: characters in the vocabulary become one token each (ignore_merges),
// anything else falls back to llama.cpp's byte-level BPE for that character only.
std::vector<int> ZenzScorer::tokenize(const std::string & text, bool add_special) const {
    const llama_vocab * vocab = llama_model_get_vocab(model_);
    std::vector<int> out;
    if (add_special) {
        const llama_token bos = llama_vocab_bos(vocab);
        if (bos >= 0) out.push_back(bos);
    }
    llama_token buf[16];
    for (size_t i = 0; i < text.size();) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        const size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        const size_t n_bytes = std::min(len, text.size() - i);
        const int n = llama_tokenize(vocab, text.data() + i, int(n_bytes), buf, 16, false, false);
        for (int k = 0; k < n; k++) out.push_back(buf[k]);
        i += n_bytes;
    }
    return out;
}

// Sequences are decoded in blocks of kBlock from their start, and a cache is reused only up to a block boundary, so the
// batches a token goes through depend on the input alone. That matters: rows are not computed bit for bit alike in
// batches of different shapes, and Q5_K weights quantize their input to 8 bits, so a rounding difference near 1e-7 can
// flip one quantized value and grow to tenths of a nat in the logits (reports/zenz-prompt-cache-0.1.28.md). Batches that
// followed the cache made scores depend on typing history. Rows from first_output on get logits.
int ZenzScorer::decode_block(const int * tokens, int n, int pos, int first_output) {
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int j = 0; j < n; j++) {
        batch.token[j] = tokens[j];
        batch.pos[j] = pos + j;
        batch.n_seq_id[j] = 1;
        batch.seq_id[j][0] = 0;
        batch.logits[j] = j >= first_output ? 1 : 0;
    }
    batch.n_tokens = n;
    const int rc = llama_decode(ctx_, batch);
    llama_batch_free(batch);
    return rc;
}

// While typing, each prompt usually extends the previous one (same context, longer reading), so only the
// tokens after the common prefix are decoded; the KV cache keeps the rest.
bool ZenzScorer::decode_prompt(const std::string & prompt) {
    if (prompt == cached_prompt_ && n_prompt_ > 0) return true;
    cached_prompt_.clear();
    llama_memory_t mem = llama_get_memory(ctx_);
    const bool add_bos = llama_vocab_get_add_bos(llama_model_get_vocab(model_));
    std::vector<int> tokens = tokenize(prompt, add_bos);
    if (tokens.empty() || int(tokens.size()) >= kContext - kMaxCandidateTokens) {
        prompt_tokens_.clear();
        n_prompt_ = 0;
        llama_memory_clear(mem, true);
        return false;
    }
    size_t k = 0;
    while (k < tokens.size() && k < prompt_tokens_.size() && tokens[k] == prompt_tokens_[k]) k++;
    if (k == tokens.size()) k--;  // re-decode the last token to get its logits
    k -= k % kBlock;
    if (!llama_memory_seq_rm(mem, 0, int(k), -1)) {
        llama_memory_clear(mem, true);
        k = 0;
    }
    int last = 0;
    for (size_t at = k; at < tokens.size();) {
        const int n = int(std::min(tokens.size() - at, size_t(kBlock)));
        const bool final_block = at + size_t(n) == tokens.size();
        if (decode_block(tokens.data() + at, n, int(at), final_block ? n - 1 : n) != 0) {
            prompt_tokens_.clear();
            n_prompt_ = 0;
            llama_memory_clear(mem, true);
            return false;
        }
        at += size_t(n);
        last = n - 1;
    }
    log_softmax(llama_get_logits_ith(ctx_, last), n_vocab_, prompt_logprobs_);
    prompt_tokens_ = tokens;
    n_prompt_ = int(tokens.size());
    cached_prompt_ = prompt;
    return true;
}

bool ZenzScorer::score(const std::string & prompt, const std::vector<std::string> & candidates, std::vector<float> & out,
                       bool append_end) {
    out.assign(candidates.size(), -INFINITY);
    if (!decode_prompt(prompt)) return false;
    llama_memory_t mem = llama_get_memory(ctx_);
    // dists[j] = log-softmax predicting token j of the previous candidate; dists[0] follows the prompt.
    // A candidate sharing k leading tokens with the previous one reuses dists[0..k] and its KV cache, so
    // one-word variants of the same sentence only decode the part after the changed word.
    std::vector<int> prev;
    std::vector<std::vector<float>> dists(1, prompt_logprobs_);
    for (size_t c = 0; c < candidates.size(); c++) {
        std::vector<int> tokens = tokenize(candidates[c], false);
        if (append_end) tokens.push_back(eos_);
        const int n = int(tokens.size());
        if (n == 0 || n_prompt_ + n >= kContext) continue;
        int k = 0;
        while (k < n && k < int(prev.size()) && tokens[k] == prev[k]) k++;
        k = std::min(k, int(dists.size()) - 1);
        k -= k % kBlock;
        // Positions [n_prompt_ + k, ...) belonged to the previous candidate; drop them.
        llama_memory_seq_rm(mem, 0, n_prompt_ + k, -1);
        dists.resize(size_t(k) + 1);
        const int to_decode = n - 1 - k;  // tokens k..n-2 yield the distributions for k+1..n-1
        for (int at = 0; at < to_decode; at += kBlock) {
            const int block = std::min(kBlock, to_decode - at);
            if (decode_block(tokens.data() + k + at, block, n_prompt_ + k + at, 0) != 0) return false;
            for (int j = 0; j < block; j++) {
                dists.emplace_back();
                log_softmax(llama_get_logits_ith(ctx_, j), n_vocab_, dists.back());
            }
        }
        double total = 0.0;
        for (int j = 0; j < n; j++) total += dists[size_t(j)][size_t(tokens[j])];
        out[c] = float(total);
        // The last token was never decoded, so the cache holds tokens 0..n-2 of this candidate.
        prev.assign(tokens.begin(), tokens.end() - 1);
    }
    llama_memory_seq_rm(mem, 0, n_prompt_, -1);
    return true;
}
