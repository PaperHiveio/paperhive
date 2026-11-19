package org.abdullahcxd.paperhive.api;

import org.abdullahcxd.paperhive.api.message.MessageCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NamespacedTest {

    @Test
    void keys() {
        assertEquals("myplugin:joins", Namespaced.of("myplugin", "joins").asString());
        assertEquals(Namespaced.of("lobby", "queue/vip"), Namespaced.parse("lobby:queue/vip"));
        assertEquals(Namespaced.paperhive("servers"), Namespaced.parse("servers"), "no namespace: paperhive's");
        assertEquals("my_cool_plugin:x", Namespaced.forPlugin("My Cool-Plugin".replace('-', ' '), "x").asString());
        assertEquals("a:b", Namespaced.of("a", "c").with("b").toString());
        assertTrue(Namespaced.of("a", "b").compareTo(Namespaced.of("a", "c")) < 0);
    }

    @Test
    void rejectsWhatWouldReadDifferently() {
        for (String bad : new String[]{"Upper:x", "a:Upper", ":x", "a:", "a b:x", "a:b:c"}) {
            assertThrows(IllegalArgumentException.class, () -> Namespaced.parse(bad), bad);
        }
    }

    @Test
    void codecsRoundTrip() {
        assertEquals(42, MessageCodec.INTEGER.decode(MessageCodec.INTEGER.encode(42)));
        assertEquals(Long.MAX_VALUE, MessageCodec.LONG.decode(MessageCodec.LONG.encode(Long.MAX_VALUE)));
        assertEquals(true, MessageCodec.BOOLEAN.decode("true"));
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.BOOLEAN.decode("yes"));
        UUID id = UUID.randomUUID();
        assertEquals(id, MessageCodec.UUID_CODEC.decode(MessageCodec.UUID_CODEC.encode(id)));
        assertEquals(TimeUnit.SECONDS, MessageCodec.enumOf(TimeUnit.class).decode("seconds"));

        record Name(String value) {}
        MessageCodec<Name> names = MessageCodec.STRING.map(Name::new, Name::value);
        assertEquals(new Name("x"), names.decode(names.encode(new Name("x"))));
    }

}
