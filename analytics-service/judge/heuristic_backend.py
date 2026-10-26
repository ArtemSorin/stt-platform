"""Заглушка вместо модели для проверки проводки (порты, протокол, приоритеты) без загрузки весов.

ВНИМАНИЕ: это НЕ FRIDA-Decisions. «Логиты» берутся из подсчёта общих слов, поэтому качество оценок не имеет
отношения к качеству модели. Формат запроса и ответа — настоящий: запрос разбирает parse_request, а ответ
собирает aggregate из самой библиотеки.
"""
import math
import re

from frida_decisions import aggregate, compile_request, parse_request
from frida_decisions.config import DecisionsConfig

_WORD = re.compile(r"[A-Za-zА-Яа-яЁё0-9]{4,}")


def stems(text: str) -> set:
    return {w.lower().replace("ё", "е")[:5] for w in _WORD.findall(text)}


def section(state: str, start: str, ends: tuple) -> str:
    """Текст между метками состояния (метки заданы построителем запросов analytics-service)."""
    parts = re.split(r"\n(?=(?:Ответ кандидата|Уточняющий вопрос|Эталонный ответ|Пометка проверяющего)[^:\n]*:)", state)
    picked = [p.split(":", 1)[1] for p in parts if p.startswith(start)]
    return " ".join(picked)


class HeuristicJudge:
    backend = "heuristic"
    state_max = 512

    def __init__(self):
        self.config = DecisionsConfig()

    def __call__(self, request):
        return self.judge_batch([request])[0]

    def judge(self, request):
        return self.judge_batch([request])[0]

    def judge_batch(self, requests):
        out = []
        for request in requests:
            parsed = parse_request(request)
            candidates = compile_request(parsed, self.config.instruction_suffixes, self.config.yes_no_default_criteria)
            state = candidates[0].state
            answer = " ".join(section(state, "Ответ кандидата", ()).split())
            reference = section(state, "Эталонный ответ", ())
            question = state.split("\n", 1)[0]
            a, r, q = stems(answer), stems(reference), stems(question)
            overall = len(a & r) / max(1, len(r))
            margins = [self._margin(parsed, c, answer, a, r, q, overall) for c in candidates]
            by_q = {}
            for c, m in zip(candidates, margins):
                by_q.setdefault(c.question_id, {})[c.option_id] = m
            out.append({"model": "FRIDA-Decisions (heuristic stub)", "answers": aggregate(parsed, candidates, margins),
                        "margins": by_q,
                        "usage": {"state_tokens": len(state) // 3, "state_truncated": False,
                                  "questions": len(parsed.questions), "candidates": len(candidates),
                                  "backend": self.backend, "milliseconds": 1.0}})
        return out

    def _margin(self, parsed, cand, answer, a, r, q, overall):
        question = parsed.questions[cand.question_id]
        qid = cand.question_id
        if question.type == "noul":
            if qid == "ignorance":
                delta = 5.0 if re.search(r"не знаю|не помню|затрудняюсь|не готовил", answer.lower()) else -5.0
            elif qid == "offtopic":
                delta = 5.0 if len(a & (r | q)) / max(1, len(a)) < 0.05 else -5.0
            else:                                                     # факт эталона: «Кандидат сообщает: «...»?»
                fact = stems(cand.instruction.split("«", 1)[-1].split("»", 1)[0])
                share = len(fact & a) / max(1, len(fact))
                delta = 8.0 * (share - 0.4)
            return delta / 2 if cand.option_id == "true" else -delta / 2
        if question.type == "ranking":
            text = stems(cand.text)
            return 4.0 * (len(text & r) - len(text & a)) / max(1, len(text))
        level = int(cand.option_id)                                     # score: пик на уровне, пропорциональном совпадению
        return -abs(level - 4 * min(1.0, overall * 1.2)) * 1.5
