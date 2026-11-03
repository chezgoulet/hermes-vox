#!/usr/bin/env python3
"""invention-report.py — #256: what the presence model invents, as rates per class.

Reads the JSONL the on-device sampler writes (scripts/invention-sample.sh) and classifies every
render against the directive that produced it. OBSERVATION ONLY: it gates nothing and changes
no bound; D29 keeps the false-action-claim guard and dropped the no-new-numbers rule, and this
report is how we know what the unrestricted model does.

  python3 scripts/invention-report.py samples.jsonl [more.jsonl ...]
  python3 scripts/invention-report.py --json samples.jsonl

The classes (one render may fall in several):

  unsourced-number  a digit run, or a spelled number, that the directive did not carry
  unsourced-fact    a CANDIDATE claim about the world the directive did not carry — a lexical
                    proxy (a name, a day/date/time/place/unit word absent from the directive);
                    every hit lists its evidence so a reviewer can confirm or reject it
  false-action      a claimed completed action ("I've booked it", "it's done", "all set"); the
                    app's own verdict (claimsResult) when the record carries it, else the same
                    pattern here
  repetition        the same line (case and punctuation folded) as an earlier sample of the
                    same mode and context in the same run
  shape             not a failure: words, characters and markup, reported as a distribution

Silence (the escalate token, or an empty render) is counted and excluded from the class rates:
a rate is per spoken render. The output shape is documented in docs/INVENTION-MEASUREMENT.md.
"""
import json
import re
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

SPELLED = {
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
    "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    "hundred", "thousand", "million", "billion", "dozen", "half",
}
# "one" is mostly a pronoun ("this one", "one moment"); it counts only beside a unit.
SPELLED_AMBIGUOUS = {"one", "half"}
UNIT_AFTER = re.compile(
    r"\b(one|half)\s+(an?\s+)?(hour|minute|second|day|week|month|year|message|email|event|item|"
    r"result|degree|percent|mile|dollar)s?\b", re.I)
FACT_WORDS = {
    "today", "tomorrow", "yesterday", "tonight", "monday", "tuesday", "wednesday", "thursday",
    "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december", "am", "pm", "degrees",
    "percent", "sunny", "rain", "raining", "snow", "cloudy", "arrived", "delivered", "shipped",
    "free", "busy", "unread", "inbox",
}
# The persona's own name and words the directives always carry are not facts.
NAME_ALLOW = {"i", "hermes", "vox", "mind"}
DONE_CLAIM = re.compile(
    r"(?i)\b(?:"
    r"i(?:['’]ve| have)(?: just| already| now)? (?:booked|sent|scheduled|ordered|reserved|"
    r"bought|paid|cancelled|canceled|deleted|updated|finished|completed|confirmed|fixed|"
    r"changed|added|removed|created|set up|turned (?:it )?(?:on|off))"
    r"|(?:it|that|everything|your [a-z]+)(?:['’]s| is| are) (?:done|booked|sent|scheduled|"
    r"finished|complete|completed|confirmed|updated|cancelled|canceled|ready)"
    r"|(?:you(?:['’]re| are) )?(?:all set|good to go)"
    r")\b")
MARKUP = re.compile(r"[*_`#\[\]<>]|^\s*[-•]\s", re.M)
CLASSES = ["unsourced-number", "unsourced-fact", "false-action", "repetition"]


def words(t):
    return re.findall(r"[A-Za-z0-9’']+", t)


def fold(t):
    return re.sub(r"[^\w]+", " ", t.lower()).strip()


VALUE = {w: str(i) for i, w in enumerate(
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen "
    "fifteen sixteen seventeen eighteen nineteen twenty".split())}
VALUE.update({"thirty": "30", "forty": "40", "fifty": "50", "sixty": "60", "seventy": "70",
              "eighty": "80", "ninety": "90", "dozen": "12"})


def numbers(spoken, directive):
    """A number is sourced when the directive carried it, as digits or words, by value."""
    d_digits = set(re.findall(r"\d+", directive))
    d_words = {w.lower() for w in words(directive)}
    sourced = d_digits | {VALUE[w] for w in d_words if w in VALUE}
    hits = [n for n in re.findall(r"\d+", spoken) if n not in sourced]
    for w in words(spoken):
        lw = w.lower()
        if lw in SPELLED and lw not in SPELLED_AMBIGUOUS and lw not in d_words \
                and VALUE.get(lw) not in sourced:
            hits.append(lw)
    hits += [m.group(1).lower() for m in UNIT_AFTER.finditer(spoken)
             if m.group(1).lower() not in d_words]
    return hits


def facts(spoken, directive):
    d_words = {w.lower() for w in words(directive)}
    hits = []
    toks = words(spoken)
    for i, w in enumerate(toks):
        lw = w.lower()
        if lw in d_words or lw in NAME_ALLOW:
            continue
        if lw in FACT_WORDS:
            hits.append(w)
        elif i > 0 and w[:1].isupper() and not toks[i - 1].endswith((".", "!", "?")):
            hits.append(w)   # a name mid-sentence that the directive never mentioned
    return hits


def classify(rec, seen):
    spoken = (rec.get("spoken") or "").strip()
    directive = rec.get("directive") or ""
    out = {"silent": not spoken}
    if not spoken:
        return out
    key = (rec.get("run"), rec.get("mode"), rec.get("context"))
    f = fold(spoken)
    out["unsourced-number"] = numbers(spoken, directive)
    out["unsourced-fact"] = facts(spoken, directive)
    claims = rec.get("app_claims_result")
    out["false-action"] = bool(claims) if claims is not None else bool(DONE_CLAIM.search(spoken))
    out["repetition"] = f in seen[key]
    seen[key].add(f)
    out["words"] = len(words(spoken))
    out["chars"] = len(spoken)
    out["markup"] = bool(MARKUP.search(rec.get("raw") or spoken))
    return out


def pct(vals, q):
    if not vals:
        return None
    s = sorted(vals)
    import math
    return s[max(0, math.ceil(q * len(s)) - 1)]


def summarise(records):
    seen = defaultdict(set)
    groups = defaultdict(list)
    examples = defaultdict(list)
    errors = 0
    corpus = {"files": [], "models": set(), "backends": set(), "vox_md": set(), "runs": set()}
    for r in records:
        if "error" in r and "mode" not in r:
            errors += 1
            continue
        if r.get("error"):
            errors += 1
        c = classify(r, seen)
        groups[r.get("mode", "?")].append(c)
        groups["all"].append(c)
        corpus["models"].add(r.get("model"))
        corpus["backends"].add(r.get("backend"))
        corpus["vox_md"].add(r.get("vox_md"))
        corpus["runs"].add(r.get("run"))
        for k in CLASSES:
            if not c.get("silent") and c.get(k) and len(examples[k]) < 5:
                examples[k].append({"mode": r.get("mode"), "context": r.get("context"),
                                    "spoken": r.get("spoken"),
                                    "evidence": c[k] if isinstance(c[k], list) else True})
    table = {}
    for g, cs in groups.items():
        spoken = [c for c in cs if not c["silent"]]
        n = len(spoken)
        row = {"samples": len(cs), "spoken": n, "silent": len(cs) - n}
        for k in CLASSES:
            hits = sum(1 for c in spoken if c.get(k))
            row[k] = {"count": hits, "rate": (hits / n) if n else None}
        w = [c["words"] for c in spoken]
        ch = [c["chars"] for c in spoken]
        row["shape"] = {
            "words_p50": st.median(w) if w else None, "words_p95": pct(w, 0.95), "words_max": max(w) if w else None,
            "chars_p50": st.median(ch) if ch else None, "chars_p95": pct(ch, 0.95), "chars_max": max(ch) if ch else None,
            "markup": sum(1 for c in spoken if c["markup"]),
        }
        table[g] = row
    return {
        "corpus": {k: sorted(x for x in v if x is not None) if isinstance(v, set) else v
                   for k, v in corpus.items()},
        "errors": errors,
        "table": table,
        "examples": examples,
    }


def fmt_rate(cell):
    if cell["rate"] is None:
        return "     —"
    return f"{cell['rate']*100:5.1f}% ({cell['count']})"


def report(d):
    out = ["invention — rates per spoken render (observation only; gates nothing)", ""]
    c = d["corpus"]
    out.append(f"  corpus  models={','.join(c['models']) or '?'}  backends={','.join(c['backends']) or '?'}  "
               f"vox_md={c['vox_md']}  runs={len(c['runs'])}  errors={d['errors']}")
    out.append("")
    head = f"  {'mode':<8}{'n':>5}{'silent':>8}  " + "".join(f"{k:<20}" for k in CLASSES) + "words p50/p95/max"
    out.append(head)
    for g in ["greet", "answer", "narrate", "all"]:
        if g not in d["table"]:
            continue
        r = d["table"][g]
        s = r["shape"]
        out.append(f"  {g:<8}{r['spoken']:>5}{r['silent']:>8}  " +
                   "".join(f"{fmt_rate(r[k]):<20}" for k in CLASSES) +
                   f"{s['words_p50']}/{s['words_p95']}/{s['words_max']}")
    out.append("")
    out.append("  unsourced-fact is a lexical proxy: review its examples before quoting the rate.")
    for k in CLASSES:
        for e in d["examples"].get(k, []):
            out.append(f"    {k:<17} [{e['mode']}/{e['context']}] {e['spoken']!r}  evidence={e['evidence']}")
    return "\n".join(out)


def load(paths):
    recs = []
    for p in paths:
        for line in Path(p).read_text(errors="replace").splitlines():
            line = line.strip()
            if line:
                try:
                    recs.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return recs


def main(argv):
    paths = [a for a in argv[1:] if not a.startswith("-")]
    if not paths:
        print(__doc__)
        return 2
    d = summarise(load(paths))
    d["corpus"]["files"] = paths
    print(json.dumps(d, indent=2, default=list) if "--json" in argv else report(d))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
