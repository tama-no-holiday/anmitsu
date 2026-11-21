// Scores conversion candidates with a zenz GGUF model through llama.cpp. No text is logged or stored.
#pragma once

#include <string>
#include <vector>

struct llama_model;
struct llama_context;

class ZenzScorer {
public:
    // Returns nullptr when the model cannot be loaded.
    static ZenzScorer * open(const std::string & model_path, int threads);
    ~ZenzScorer();

    // zenz-v3 prompt: "" + katakana reading + "" (an optional left context goes first after "").
    static std::string prompt(const std::string & katakana_reading, const std::string & left_context = "");

    // Fills out[i] with log P(candidates[i] [+ </s>] | prompt) in nats. Returns false on a llama.cpp error.
    // Without append_end the candidate is scored as-is (e.g. a left context followed by its U+EE00 mark).
    bool score(const std::string & prompt, const std::vector<std::string> & candidates, std::vector<float> & out,
               bool append_end = true);

    std::vector<int> tokenize(const std::string & text, bool add_special) const;

private:
    ZenzScorer() = default;
    int decode_block(const int * tokens, int n, int pos, int first_output);
    bool decode_prompt(const std::string & prompt);

    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    int n_vocab_ = 0;
    int eos_ = -1;
    std::string cached_prompt_;
    std::vector<int> prompt_tokens_;  // tokens of cached_prompt_, kept in the KV cache at positions 0..n-1
    int n_prompt_ = 0;
    std::vector<float> prompt_logprobs_;  // log-softmax after the last prompt token
};
