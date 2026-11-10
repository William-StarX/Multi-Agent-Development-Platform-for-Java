package io.jdda.poc;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Scripted tool choices used only by the offline repair acceptance run. */
public final class BugfixFixtureModel {
    public static void main(String[] args) throws Exception {
        AgentPoc.run(args, AgentRoleProfile.BUGFIX, "scripted-fixture", prompt -> {
            long toolResponses = prompt.getInstructions().stream()
                    .filter(message -> message.getMessageType() == org.springframework.ai.chat.messages.MessageType.TOOL).count();
            var reply = switch ((int) toolResponses) {
                case 0 -> call("read", "readFixture", "{}");
                case 1 -> call("edit", "replaceExpression", "{\"expression\":\"Math.max(0L, subtotalCents - discountCents)\"}");
                default -> new AssistantMessage("The previous expression added the discount. The payable amount now subtracts it and floors at zero.");
            };
            return new ChatResponse(List.of(new Generation(reply)));
        });
    }

    private static AssistantMessage call(String id, String name, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build();
    }
}
