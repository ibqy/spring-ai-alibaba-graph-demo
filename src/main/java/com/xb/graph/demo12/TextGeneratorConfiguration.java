package com.xb.graph.demo12;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.xb.graph.TextGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class TextGeneratorConfiguration {
    @Bean
    public TextGenerator textGenerator(Environment environment) {
        String mode = environment.getProperty("graph.model.mode", "offline");
        return switch (mode) {
            case "offline" -> new OfflineTextGenerator();
            case "dashscope" -> {
                String key = environment.getProperty("DASHSCOPE_API_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("dashscope 模式需要 DASHSCOPE_API_KEY 环境变量不能为空");
                }
                var api = DashScopeApi.builder().apiKey(key).build();
                var model = DashScopeChatModel.builder().dashScopeApi(api)
                        .defaultOptions(DashScopeChatOptions.builder().model("qwen-plus").build()).build();
                yield new DashScopeTextGenerator(model);
            }
            default -> throw new IllegalArgumentException("graph.model.mode 仅支持 offline 或 dashscope");
        };
    }

    /** 确定性教学替身：压缩空白、截取首句；不进行语义推理，也不调用网络。 */
    private static final class OfflineTextGenerator implements TextGenerator {
        @Override
        public String generate(String prompt) {
            String text = requirePrompt(prompt).replaceAll("\\s+", " ");
            int sentenceEnd = text.indexOf('。');
            if (sentenceEnd >= 0) text = text.substring(0, sentenceEnd + 1);
            int codePoints = text.codePointCount(0, text.length());
            if (codePoints > 36) text = text.substring(0, text.offsetByCodePoints(0, 36)) + "…";
            return "【离线教学模拟】摘要：" + text;
        }

        @Override
        public String mode() { return "offline"; }
    }

    private record DashScopeTextGenerator(DashScopeChatModel model) implements TextGenerator {
        @Override
        public String generate(String prompt) {
            String text = requirePrompt(prompt);
            // 任务由调用方定义；这里只返回文本，不注册工具或解释模型输出为动作。
            String output = model.call(text);
            if (output == null || output.isBlank()) {
                throw new IllegalStateException("DashScope 返回空文本");
            }
            return output.strip();
        }

        @Override
        public String mode() { return "dashscope"; }
    }

    private static String requirePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 必须是非空字符串");
        }
        return prompt.strip();
    }
}
