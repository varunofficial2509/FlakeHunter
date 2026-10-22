package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TagListTest {

    static final class Tag {
        final String name;

        Tag(String name) {
            this.name = name;
        }
    }

    @Test
    void listsTagsInTheOrderAdded() {
        Set<Tag> tags = new HashSet<>();
        tags.add(new Tag("urgent"));
        tags.add(new Tag("billing"));
        String listed = tags.stream().map(t -> t.name).collect(Collectors.joining(","));
        assertEquals("urgent,billing", listed);
    }

    @Test
    void keepsTagsUnique() {
        Set<String> tags = new LinkedHashSet<>(java.util.List.of("a", "b", "a"));
        assertEquals(2, tags.size());
    }
}
