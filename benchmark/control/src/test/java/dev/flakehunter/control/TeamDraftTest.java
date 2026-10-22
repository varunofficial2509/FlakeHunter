package dev.flakehunter.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamDraftTest {

    @Test
    void firstTeamHasACaptain() {
        List<String> players = new ArrayList<>(List.of("captain-ana", "captain-raj", "li", "omar"));
        Collections.shuffle(players);
        List<String> firstTeam = players.subList(0, 2);
        assertTrue(firstTeam.stream().anyMatch(p -> p.startsWith("captain")), "first team: " + firstTeam);
    }
}
