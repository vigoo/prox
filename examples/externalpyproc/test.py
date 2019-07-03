import sys

def run():
    stop = False

    while not stop:
        line = sys.stdin.readline().strip()

        if len(line) == 0:
            stop = True
        else:
            print line + "!?!?"
            sys.stdout.flush()

run()
