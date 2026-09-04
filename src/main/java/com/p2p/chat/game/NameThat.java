package com.p2p.chat.game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * "Name That..." — the system serves hidden movies, songs and video games, and
 * reveals one hint at a time. Players answer with {@code @guess <title>}.
 * Categories: {@code @guess movie}, {@code @guess song}, {@code @guess game}.
 * The pool is a curated built-in list (works offline, no API keys).
 */
public final class NameThat implements Game {
    private static final Clue[] MOVIES = {
            cl("The Shawshank Redemption", "Prison-set; a banker is wrongly convicted and tunnelled out",
                    "The beach scene at the end", "Warden's 'Brooks was here'"),
            cl("Inception", "Dreams inside dreams; a spinning top at the end", "Director of the Dark Knight trilogy",
                    "A van, a hotel, and a mountain of snow", "Time runs faster the deeper you dream"),
            cl("The Matrix", "Red pill or blue pill; follow the white rabbit", "Dodge bullets in slow motion",
                    "Morpheus offers the truth", "Machines farm humans as batteries"),
            cl("Titanic", "A luxury liner hits ice; 'I'm flying!'", "Leo and Kate; 1912", "Rose lets go",
                    "Celine Dion sang the theme"),
            cl("The Godfather", "A mafia family; an offer you can't refuse", "A horse head in a bed",
                    "Michael rises to the top", "Coppola, 1972"),
            cl("Jaws", "A shark terrorizes a beach town", "'We're gonna need a bigger boat'",
                    "Spielberg, 1975", "The shark's dorsal fin"),
            cl("Avatar", "Blue-skinned aliens on a moon called Pandora", "Jake Sully; 3D box-office king",
                    "Flying banshees", "The valuable mineral is unobtanium"),
            cl("Jurassic Park", "Dinosaurs cloned from mosquitoes in amber", "'Life finds a way'",
                    "Isla Nublar", "Ian Malcolm and chaos theory", ""),
            cl("The Dark Knight", "A clown prince of crime; 'Why so serious?'", "Heath Ledger's Oscar",
                    "Two-Face's coin flip", "Gotham needs a hero it deserves"),
    };

    private static final Clue[] SONGS = {
            cl("Bohemian Rhapsody", "Queen; 'Scaramouche, Scaramouche'", "'Is this the real life?'",
                    "Galileo galileo", "Over six minutes long"),
            cl("Smells Like Teen Spirit", "Nirvana's grunge anthem", "'Here we are now, entertain us'",
                    "1991 Nevermind", "Kurt Cobain's famous riff"),
            cl("Hey Jude", "The Beatles; a huge 'na na na' outro", "'Take a sad song and make it better'",
                    "Paul wrote it for Julian Lennon", "Over seven minutes"),
            cl("Rolling in the Deep", "Adele's breakout hit", "'We could have had it all'",
                    "From the 2010 album 21", "She stomps and claps a beat"),
            cl("Despacito", "Luis Fonsi & Daddy Yankee; a Spanish mega-hit", "A Bieber remix blew it up",
                    "The title means 'slowly'", "Broke streaming records"),
            cl("Shape of You", "Ed Sheeran; 'the club isn't the best place to find a lover'",
                    "2017 worldwide hit", "Has a gym and a dance floor in the lyrics",
                    "The beat was reworked from a friend's idea"),
            cl("Don't Stop Believin'", "Journey; the song everyone sings at the end of the night",
                    "A small town girl", "1981, Escape album",
                    "Samples in everything from Glee to The Sopranos"),
            cl("Hotel California", "The Eagles; 'you can check out any time you like, but you can never leave'",
                    "1976; a desert inn", "A long dueling guitar solo",
                    "Written half by Don Henley and Glenn Frey"),
            cl("The Sound of Silence", "Simon & Garfunkel; 'hello darkness, my old friend'",
                    "1964 folk classic", "Paul Simon wrote it at 21", "Disturbed's cover went viral"),
    };

    private static final Clue[] GAMES = {
            cl("Tetris", "Stack falling blocks and clear lines", "Made by a Soviet programmer in 1984",
                    "Shapes are called tetriminoes", "One of the best-selling games ever"),
            cl("Minecraft", "Dig, build, survive in a blocky world", "Creeper... sssss",
                    "Kill the Ender Dragon to 'finish'", "Notch sold it to Microsoft"),
            cl("Fortnite", "100 players, last one standing", "You can build ramps mid-fight",
                    "The storm circle shrinks", "Emotes everywhere"),
            cl("Super Mario Bros.", "A plumber saves a princess", "1985 NES classic", "World 1-1 is legendary",
                    "Question blocks, warp pipes, goombas"),
            cl("The Legend of Zelda: Ocarina of Time", "Link uses an ocarina to travel through time",
                    "Great Deku Tree, Gorons", "Ganon's castle looms", "1998 N64 masterpiece"),
            cl("World of Warcraft", "An MMORPG where you pick Horde or Alliance", "Explore Azeroth",
                    "Vanilla level cap was 60", "Blizzard's juggernaut"),
            cl("Among Us", "Impostors among a crew; 'sus'", "Do tasks and vote to eject",
                    "A tiny indie that exploded on stream", "Space-themed deduction"),
            cl("Counter-Strike", "A round-based 5v5 shooter", "Bomb site A or B on Dust II",
                    "Started life as a Half-Life mod", "Trade and defuse culture"),
            cl("The Witcher 3: Wild Hunt", "Geralt hunts monsters and searches for Ciri", "Play Gwent in-world",
                    "CD Projekt Red", "Its big DLCs are Blood & Wine and Hearts of Stone"),
            cl("Portal 2", "A test chamber puzzler with a portal gun", "GLaDOS and Wheatley",
                    "Companion cube", "Cave Johnson's lemons"),
    };

    private Clue current;
    private String category;
    private int hint;
    private boolean solved;

    @Override
    public String handle(String user, String args) {
        String a = args.trim().toLowerCase();
        if (a.isEmpty()) {
            return usage();
        }
        String[] p = a.split("\\s+");
        String act = p[0];

        if (act.equals("start") || act.equals("new")) {
            String cat = p.length > 1 ? p[1].trim() : randomCategory();
            return startRound(cat);
        }
        if (act.equals("hint")) {
            if (current == null) {
                return "Start a round first: @guess start <movie|song|game>";
            }
            hint++;
            while (hint < current.hints.length && (current.hints[hint] == null || current.hints[hint].isEmpty())) {
                hint++;
            }
            if (hint < current.hints.length) {
                return "Hint " + (hint + 1) + ": " + current.hints[hint];
            }
            return "Out of hints! The answer was: " + current.title + "\nNew round: @guess start <category>";
        }
        if (act.equals("quit") || act.equals("end")) {
            String ans = current != null ? " The answer was: " + current.title : "";
            current = null;
            return "Name That round ended." + ans;
        }

        if (current == null) {
            return "Start a round first: @guess start <movie|song|game>";
        }
        if (solved) {
            return "Solved! The answer was " + current.title.toUpperCase()
                    + ". New round: @guess start <category>";
        }
        if (normalize(args).equals(normalize(current.title))) {
            solved = true;
            String title = current.title;
            current = null;
            return "[Correct] " + user + " guessed it: " + title.toUpperCase() + "!\n"
                    + "New round: @guess start <category>";
        }
        String nextHint = hint + 1 < current.hints.length && current.hints[hint + 1] != null
                ? " Next hint: @guess hint" : " No more hints: @guess take-any-answer";
        return "Not that, " + user + "." + nextHint;
    }

    private String startRound(String cat) {
        Clue[] pool = switch (cat) {
            case "movie", "movies" -> MOVIES;
            case "song", "songs", "music" -> SONGS;
            case "game", "games" -> GAMES;
            default -> null;
        };
        if (pool == null) {
            return "Category must be movie, song or game. E.g. @guess start movie";
        }
        current = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        category = cat;
        hint = 0;
        solved = false;
        String h1 = current.hints[0] == null || current.hints[0].isEmpty()
                ? current.hints[1] : current.hints[0];
        return "Name That " + cat.toUpperCase() + "!\nGuess the title with @guess <title>.\n"
                + "Hint 1: " + h1;
    }

    private static String randomCategory() {
        int r = ThreadLocalRandom.current().nextInt(3);
        return r == 0 ? "movie" : r == 1 ? "song" : "game";
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static Clue cl(String title, String... hints) {
        return new Clue(title, hints);
    }

    private static String usage() {
        return "Name That... (movies/songs/games):\n"
                + "  @guess start <movie|song|game>   start a round\n"
                + "  @guess hint                      reveal the next hint\n"
                + "  @guess <title>                   answer the current clue\n"
                + "  @guess quit                      end the round";
    }

    private record Clue(String title, String[] hints) {
    }
}