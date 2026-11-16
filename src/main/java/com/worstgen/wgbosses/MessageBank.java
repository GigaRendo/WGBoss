package com.worstgen.wgbosses;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Taunts, names and taglish lines. Editable in config/wgbosses/messages.json.
 * Placeholders: {player} {boss} {mob}
 * Personalities: ego, light, coward, hype, genz
 * Events: spawn, hit, phase, lowhp, kill, death, skill, despawn
 */
public final class MessageBank {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Map<String, Map<String, List<String>>> taunts = new LinkedHashMap<>();
    public Map<String, List<String>> taglish = new LinkedHashMap<>();
    public List<String> playerTemplates = new ArrayList<>();
    public List<String> coolNames = new ArrayList<>();

    public MessageBank() { defaults(); }

    private void add(String p, String e, String... lines) {
        taunts.computeIfAbsent(p, k -> new LinkedHashMap<>()).computeIfAbsent(e, k -> new ArrayList<>()).addAll(Arrays.asList(lines));
    }

    private void defaults() {
        // ---------------- EGO ----------------
        add("ego", "spawn", "HAHAHAHA!! Did you really think you could play in peace today?!",
                "Kneel, insects!! {boss} has arrived!!", "Which of you weaklings wants to be first?",
                "I have come. Rejoice... then scream.");
        add("ego", "hit", "Is that all you have, {player}?! My grandma hits harder!", "Pathetic. You barely tickled me, {player}.",
                "HAHAHA {player} sooo weak, I didn't even feel it!", "You call that an attack, {player}?",
                "Hit me again, {player}, I'm getting bored.", "Cute, {player}. Really cute.");
        add("ego", "phase", "You made me serious. Big mistake!!", "THIS is my real power!!", "Now you've awakened my pride, mortals!",
                "Enjoy your hope while it lasts.");
        add("ego", "lowhp", "Impossible... you can't have hurt me this much!!", "This isn't over!! I still have tricks left!!",
                "I refuse to lose to {player}!!");
        add("ego", "kill", "{player} died?? how embarrassinggg", "One down. Who's next?", "Rest in pieces, {player}!");
        add("ego", "death", "I... lost?! To these weaklings?!", "Remember my name... {boss}... you'll never forget it...",
                "Tch... you win this time. THIS TIME.");
        add("ego", "skill", "Try dodging THIS!", "Behold my power!", "You cannot escape!!", "Watch closely, peasants.");
        add("ego", "despawn", "Boring. None of you were worth my time.", "I'm leaving. Come back when you're stronger.");

        // ---------------- LIGHT (calm genius) ----------------
        add("light", "spawn", "Everything is proceeding exactly as I calculated.", "Good. You've all gathered in one place. How convenient.",
                "Do you understand yet? You were never going to win.");
        add("light", "hit", "I see, you damaged me but it doesn't affect my critical thinking. I'll crush you all!!",
                "Can you guys do better than that?", "Predictable, {player}. Truly predictable.",
                "Interesting move, {player}. It changes nothing.", "Pathetic.");
        add("light", "phase", "Phase two. Exactly as I planned.", "You've reached the next stage of my plan. Unfortunately for you.",
                "Checkmate is only a matter of time now.");
        add("light", "lowhp", "Hm. A small miscalculation. It won't happen again.", "Even this was within my predictions...",
                "Clever, {player}. But it's too late.");
        add("light", "kill", "{player} fell right into my trap.", "One piece removed from the board.", "Foolish, {player}. Foolish.");
        add("light", "death", "Impossible... I calculated everything... how...", "So this is how it ends... how disappointing.");
        add("light", "skill", "Watch carefully. This is where you lose.", "Weaklings tremble!!", "Step by step, I'll corner you.");
        add("light", "despawn", "Retreat is also a strategy. Remember that.");

        // ---------------- COWARD ----------------
        add("coward", "spawn", "U-um... hello? I didn't mean to be here...", "P-please be gentle with me, I'm new at this...",
                "Eep!! so many players!! I only wanted to eat grass...");
        add("coward", "hit", "Ow ow ow!! please stopp!!", "P-please don't hurt me, {player}...", "Waaah {player} is so scaryyy!!",
                "M-mercy!! I'll give you my lunch!!", "It hurtss please stopp!!", "Why is everyone so mean to me...");
        add("coward", "phase", "N-no no no it's getting worse!!", "I-I'm changing?! Mommy!!", "Please don't make me angry, I don't wanna hurt anyone!!");
        add("coward", "lowhp", "I'm sorry I'm sorry I'm sorry!!", "Pleaseee I have a family!!", "Don't kill me, {player}, I'll be good!!");
        add("coward", "kill", "I'm sorry {player}... I didn't mean to hurt youuu...", "Oh no... I did that...?");
        add("coward", "death", "Tell my mom... I tried my best...", "I knew I should've stayed in bed...", "Thank you for being gentle... kind of...");
        add("coward", "skill", "Sorry sorry sorry!!", "Please dodge this one!!", "I'm not doing this on purposee!!");
        add("coward", "despawn", "I'm going home now... bye...", "This is too scary, I quit!!");

        // ---------------- HYPE (anime) ----------------
        add("hype", "spawn", "This is where the real fight begins!!", "Burn bright, my soul!! Let's goooo!!",
                "A new challenger approaches... and it's ME!!");
        add("hype", "hit", "Is that your limit, {player}?! Show me MORE!!", "Good hit!! But I'm just getting started!!",
                "That fires me up even more!!", "I won't back down, {player}!!");
        add("hype", "phase", "My power isn't done growing!!", "I'll surpass my limits!!", "You've awakened something dormant!!",
                "THIS IS MY TRUE FORM!!");
        add("hype", "lowhp", "Not yet!! I still have one more miracle left!!", "I refuse to fall here!! Not like this!!",
                "My spirit isn't broken, {player}!!");
        add("hype", "kill", "{player}... you fought well. Rest now.", "Another warrior falls!!", "Don't give up, {player}... oh wait.");
        add("hype", "death", "Haha... what a fight... worth it...", "You were all great opponents... let's fight again...",
                "I... have no regrets...!!");
        add("hype", "skill", "Take THIS!!", "Special move!! No holding back!!", "Feel my full power!!");
        add("hype", "despawn", "The battle isn't over... I'll be back stronger!!");

        // ---------------- GEN Z ----------------
        add("genz", "spawn", "yo chat, {boss} just logged in, you're cooked.", "bro really thought today would be chill",
                "main character energy fr, everyone else is an NPC.");
        add("genz", "hit", "bro really thought that was damage, {player}.", "skill issue, {player}.", "{player} is NPC behavior fr fr",
                "touch grass, {player}, then come back stronger.", "not the weak hit, {player} bruhhh", "no cap that tickled",
                "cope harder, {player}.", "lowkey embarrassing, {player}.");
        add("genz", "phase", "ok now it's actually getting serious, chat.", "plot twist: I have a second health bar.",
                "ratio + L + you fell off, I'm in phase two now.", "bro thought we were done lmao");
        add("genz", "lowhp", "ok wait this isn't funny anymore??", "lag. definitely lag.", "someone report {player}, this is unfair.",
                "bro stop I'm literally at 1 hp");
        add("genz", "kill", "gg ez, {player}.", "{player} just got deleted, no re.", "report {player} for feeding.",
                "L + ratio + {player} is dead.");
        add("genz", "death", "gg no re... I'm uninstalling.", "ok that was rigged but gg.", "I'll be back in the next lobby, chat.");
        add("genz", "skill", "watch this clip, chat.", "get cooked.", "this one's gonna be in the highlights fr.");
        add("genz", "despawn", "this lobby is boring, I'm queueing out.", "touching grass, brb.");

        // ---------------- TAGLISH (rare) ----------------
        taglish.put("spawn", new ArrayList<>(List.of("Hoy mga bobo, nandito na ako!!", "Pasensya na, late ako, nasaan na kayo?")));
        taglish.put("hit", new ArrayList<>(List.of("Ang hina mo naman, {player}!!", "Aray ko po!! ...joke, wala yun {player}.",
                "Iyak ka na ba, {player}?", "Hoy {player}, tama na yan, pagod ka na yata.")));
        taglish.put("phase", new ArrayList<>(List.of("Ngayon, seryoso na ako!!", "Hindi pa yan ang totoong lakas ko!!")));
        taglish.put("kill", new ArrayList<>(List.of("Ay, patay si {player}. Sayang naman.", "Bawal mamatay, {player}, utang mo pa sa akin!!")));
        taglish.put("death", new ArrayList<>(List.of("Hindi... paanong... natalo ako...", "Sige na nga, panalo na kayo. Kainis!!")));

        // ---------------- NAMES ----------------
        playerTemplates.addAll(List.of(
                "{player}'s {mob} husband", "{player}'s widow", "{player}'s evil twin {mob}", "{player}'s disappointed {mob} mom",
                "Doctor {player}'s {mob}", "Sir {player} the {mob}", "{player}'s {mob} landlord", "{player}'s emotional support {mob}",
                "Ex-{mob} of {player}", "{player}'s final boss", "{player}'s {mob} nemesis", "{player}'s {mob} debt collector",
                "{player}'s unpaid {mob} intern", "The {mob} that {player} owes money", "{player}'s {mob} stepdad",
                "{player}'s long lost {mob} cousin", "Lord {mob}, slayer of {player}", "Big {mob} Energy ({player}'s fault)",
                "{player}'s {mob} situationship", "{player}'s {mob} roommate"));
        coolNames.addAll(List.of(
                "{mob} the Unbothered", "Overlord {mob}", "{mob} of a Thousand Regrets", "The Last {mob}", "Eclipse {mob}",
                "Titan {mob}", "Sir Chonk the {mob}", "Grimfang the {mob} Slayer", "{mob}, Destroyer of Weekends",
                "The Unpaid {mob}", "Emperor {mob} the Third", "Mr. Final Exam ({mob})", "{mob} of the Void Tax"));
    }

    public String line(String personality, String event, double taglishChance) {
        if (Util.chance(taglishChance)) {
            List<String> tl = taglish.get(event);
            if (tl != null && !tl.isEmpty()) return Util.pick(tl);
        }
        Map<String, List<String>> p = taunts.get(personality);
        if (p == null) return null;
        List<String> l = p.get(event);
        if (l == null || l.isEmpty()) return null;
        return Util.pick(l);
    }

    public static MessageBank load() {
        Path f = Config.dir().resolve("messages.json");
        MessageBank m = null;
        try {
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) { m = GSON.fromJson(r, MessageBank.class); }
            }
        } catch (Exception e) {
            WGBosses.LOG.error("Could not read messages.json, using defaults", e);
        }
        if (m == null) m = new MessageBank();
        if (m.taunts == null || m.taunts.isEmpty()) m.taunts = new MessageBank().taunts;
        if (m.playerTemplates == null || m.playerTemplates.isEmpty()) m.playerTemplates = new MessageBank().playerTemplates;
        if (m.coolNames == null || m.coolNames.isEmpty()) m.coolNames = new MessageBank().coolNames;
        try (Writer w = Files.newBufferedWriter(f)) { GSON.toJson(m, w); } catch (Exception ignored) {}
        return m;
    }
}
