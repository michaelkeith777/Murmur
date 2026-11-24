"""Builds Murmur's bundled English word list (app/src/main/assets/words.txt) from
hermitdave/FrequencyWords en_50k (OpenSubtitles 2018 counts, CC BY-SA 4.0).

    curl -O https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt
    python3 tools/make_words.py en_50k.txt app/src/main/assets/words.txt
"""
import math, re, sys
src, out = sys.argv[1], sys.argv[2]
counts = {}
for line in open(src, encoding='utf-8'):
    parts = line.split()
    if len(parts) != 2: continue
    w, c = parts[0], int(parts[1])
    if not re.fullmatch(r"[a-z]+", w): continue
    if len(w) == 1 and w not in ("a", "i", "u"): continue
    counts[w] = counts.get(w, 0) + c
# Subtitles split contractions ("don" + "'t"); rebuild them and drop the halves.
stems_t = "don didn isn wasn wouldn couldn aren doesn haven hasn shouldn weren hadn mustn needn".split()
for s in stems_t: counts.pop(s, None)
# Apostrophe-less spellings people type for contractions: let autocorrect fix them.
for w in ("dont im cant youre didnt doesnt isnt wasnt wouldnt couldnt shouldnt wont havent hasnt arent "
          "werent thats whats theres ive youll theyre hes shes theyll weve youve theyve youd itll heres "
          "whos wheres aint hadnt mustnt yall").split():
    counts.pop(w, None)
contractions = {
 "don't":6.6,"I'm":6.4,"it's":6.3,"that's":6.0,"didn't":6.0,"can't":5.9,"you're":5.9,"I'll":5.8,
 "what's":5.7,"I've":5.6,"we're":5.6,"he's":5.6,"let's":5.6,"isn't":5.6,"there's":5.5,"doesn't":5.5,
 "I'd":5.5,"she's":5.4,"they're":5.4,"won't":5.4,"wasn't":5.4,"wouldn't":5.4,"couldn't":5.3,
 "haven't":5.2,"you'll":5.2,"we'll":5.2,"aren't":5.2,"you've":5.0,"we've":5.0,"here's":5.0,"who's":5.0,
 "where's":5.0,"ain't":5.0,"you'd":4.9,"shouldn't":4.8,"hasn't":4.7,"he'll":4.6,"they'll":4.6,"he'd":4.6,
 "weren't":4.6,"how's":4.6,"ma'am":4.6,"they've":4.5,"we'd":4.5,"it'll":4.4,"hadn't":4.4,"she'll":4.3,
 "she'd":4.3,"they'd":4.3,"o'clock":4.3,"would've":4.3,"could've":4.2,"should've":4.2,"that'll":4.2,
 "that'd":4.2,"it'd":4.0,"y'all":4.0,"must've":3.8,"how'd":3.8,"who'd":3.6,"why'd":3.6,"where'd":3.6,
 "there'll":3.6,"might've":3.5,"what're":3.5,"mustn't":3.3,"what'll":3.3,"needn't":3.0,
}
if "won" in counts: counts["won"] //= 4
# Never offer these as a suggestion or correction (they can still be typed).
quiet = set("""fuck fucking fucked fucker fuckin motherfucker shit shitty bitch bitches bastard ass asshole dick
dickhead cock pussy cunt whore slut damn goddamn crap piss pissed""".split())
# Slurs are left out entirely.
banned = set("""nigger nigga niggas niggers faggot faggots fag fags retard retarded spic spics kike kikes chink chinks
gook gooks wetback tranny trannies dyke dykes coon coons""".split())
# Texting words people mean on purpose.
for w in "u ur lol omg btw idk tbh lmao pls plz thx ty np rn bc imo fyi asap ya yea yeah nah brb wyd hbu smh ikr ttyl".split():
    counts[w] = max(counts.get(w, 0), 30000)
rows = []
for w, c in counts.items():
    if w in banned: continue
    z = math.log10(c)
    disp = "I" if w == "i" else w
    rows.append((disp, z, w in quiet))
for w, z in contractions.items():
    rows.append((w, z, False))
rows.sort(key=lambda r: -r[1])
with open(out, "w", encoding="utf-8") as f:
    f.write("# Murmur word list. Counts from hermitdave/FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0.\n")
    f.write("# word<TAB>frequency (log10 count x 100); a leading ! means never suggest it.\n")
    for disp, z, q in rows:
        f.write(("!" if q else "") + disp + "\t" + str(int(round(z * 100))) + "\n")
print(len(rows))
