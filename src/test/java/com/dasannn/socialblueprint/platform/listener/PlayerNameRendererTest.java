package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.domain.PlayerNameFormat;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlayerNameRendererTest {
    @Test void tabAndChatSharePrefixNameRendering() {
        String prefix = "&7[&a||&7]";
        Component chatName = PlayerNameRenderer.join(ColorParser.parse(prefix), Component.text("Alex"));
        Component tabName = PlayerNameRenderer.name(prefix, "Alex");
        assertThat(tabName).isEqualTo(chatName);
        assertThat(AsyncChatListener.extractPlainText(tabName)).isEqualTo("[||] Alex");
        assertThat(PlayerNameFormat.name("", "Alex")).isEqualTo("Alex");
        assertThat(AsyncChatListener.extractPlainText(PlayerNameRenderer.name("", "Alex"))).isEqualTo("Alex");
    }
}
