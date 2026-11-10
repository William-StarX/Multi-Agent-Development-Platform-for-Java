package io.jdda.poc;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

final class CallBudget extends ModelInterceptor {
    private final int limit;
    int calls;
    int inputTokens;
    int outputTokens;

    CallBudget() { this(4); }

    CallBudget(int limit) { this.limit = limit; }

    @Override public String getName() { return "poc_call_budget"; }

    @Override public synchronized ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (calls >= limit) throw new IllegalStateException("Model call limit reached.");
        calls++;
        ModelResponse response = handler.call(request);
        if (response.getChatResponse() != null) {
            var usage = response.getChatResponse().getMetadata().getUsage();
            inputTokens += usage.getPromptTokens();
            outputTokens += usage.getCompletionTokens();
        }
        return response;
    }
}
