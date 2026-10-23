"""Check complete retirement and two ordered trace epochs across hart stop."""
import argparse
import json
from pathlib import Path

from decoder.artifacts import write_json
from decoder.tests.check_implicit_return import check


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',nargs='?',type=Path,default=Path('build/validation/hart-stop'))
    args=parser.parse_args(argv)
    captures=sorted({p.parent.parent for p in args.directory.rglob('dut/trace.json')})
    if not captures:raise RuntimeError('no actual hart-stop captures')
    write_json(args.directory/'summary.json',{'status':'failed','error':'validation incomplete'})
    results=[]
    for root in captures:
        result=check(root)
        try:
            packets=[json.loads(line) for line in (root/'dut/packets.jsonl').read_text().splitlines()]
            syncs=[p for p in packets if p['packet_type']=='Format30']
            supports=[p['fields'] for p in packets if p['packet_type']=='Format33']
            if len(syncs)!=2 or [p['reported_pc'] for p in syncs]!=['0x80001000','0x80002000']:
                raise ValueError('halt/reset must preserve the old segment and synchronize the resumed one')
            # EnImRet can be programmed before enable, producing a legitimate
            # initial support declaration while instruction tracing is off.
            if supports and supports[0]['qual_status']==0 and not supports[0]['ienable']:
                supports=supports[1:]
            if [(p['qual_status'],p['ienable']) for p in supports]!=[(0,1),(1,1),(1,0)]:
                raise ValueError('missing or repeated ordered trace-end support')
            if any(p['packet_type']=='Format31' for p in packets):
                raise ValueError('hart stop marker is not an architectural exception')
        except (OSError,ValueError,KeyError) as exc:
            result.update(status='failed',error=str(exc));write_json(root/'diff/comparison.json',result)
            raise
        result['ordered_stop_epochs']=2
        write_json(root/'diff/comparison.json',result)
        results.append(result)
    write_json(args.directory/'summary.json',{'status':'passed','captures':len(results),
        'decoded_instructions':sum(r['decoded_instructions'] for r in results)})


if __name__=='__main__':main()
