"""Deterministic counterexamples use exactly the evaluator imported by test_seamless."""
import ast
from pathlib import Path
from types import SimpleNamespace
from playback_trace import evaluate_playback


def row(at, position, stable=True, ready=4, seeking=False):
    return [at, position, False, True, True, False, False, ready, seeking, False, stable, 1.0]


def cases():
    good = [row(t, 3+t/1000) for t in range(0, 801, 20)]
    forward = [row(0, 3)] + [row(t, 6+(t-20)/1000) for t in range(20, 801, 20)]
    backward = [row(0, 3), row(20, 0)] + [row(t, 3+(t-40)/1000) for t in range(40, 801, 20)]
    stalled = [row(t, 3) for t in range(0, 801, 20)]
    loading = [row(0, 0, False, 0), row(500, 1, False, 1, True)] + [row(t+1500, 3+t/1000) for t in range(0, 801, 20)]
    return {'forward 3->6 in 20ms': (forward, False),
            'backward 3->0->3': (backward, False), '800ms stall': (stalled, False),
            'continuous playback': (good, True), 'initial load/seek then continuous': (loading, True),
            'missing samples': ([], False), 'too few samples': ([good[0], good[-1]], False),
            'short observation': (good[:8], False),
            'later seek must not be filtered': (good[:10]+[row(200, 0, True, 1, True)]+good[11:], False)}


def self_checks():
    return [(name, evaluate_playback(rows, 3, 0)['ok'] == expected)
            for name, (rows, expected) in cases().items()]


def reviewed_counterexample(path):
    """Execute the reviewed file's actual AST, without importing its browser/server fixture."""
    tree = ast.parse(Path(path).read_text())
    jumps = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'jumps')
    branch = next(n for n in ast.walk(tree) if isinstance(n, ast.If) and isinstance(n.test, ast.Name) and n.test.id == 'gecko')
    start = next(i for i,n in enumerate(branch.body) if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='settled' for t in n.targets))
    code = compile(ast.Module(body=[jumps]+branch.body[start:start+3],type_ignores=[]), str(path), 'exec')
    for name, (rows, expected) in list(cases().items())[:3]:
        answers=[]
        env={'seen':rows,'run':SimpleNamespace(start=3),'after':[True,True,True,True,rows[-1][1]],
             'tag':'counterexample','check':lambda label,ok,*info: answers.append(bool(ok))}
        exec(code,env)
        assert answers == [True], (name,answers)
        assert not evaluate_playback(rows,3,0)['ok'], name
        print('PASS: reviewed assertion falsely accepts; corrected evaluator rejects:',name)


if __name__ == '__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--reviewed');args=p.parse_args()
    if args.reviewed: reviewed_counterexample(args.reviewed)
    results=self_checks()
    for name,ok in results:
        print(('OK   ' if ok else 'FAIL ')+name)
    print('ERGEBNIS: %d von %d' % (sum(ok for _,ok in results),len(results)))
    raise SystemExit(0 if all(ok for _,ok in results) else 1)
