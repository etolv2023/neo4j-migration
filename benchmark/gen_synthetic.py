import random, sys
N = int(sys.argv[1]); R = 2 * N
LABELS = ["Booking", "Service", "Customer", "Invoice", "Staff", "Room", "Payment", "Review"]
TYPES = ["HAS_SERVICE", "FOR_CUSTOMER", "BILLED_BY"]
ORGS = 200
rnd = random.Random(42)

def org(i):
    return (i * 2654435761 % 2**32) % ORGS + 1

def label(i):
    base = LABELS[i % 8]
    return base + (";Common" if i % 5 == 0 else "")

# Live lost ~3% of the ids in runs, and ~0.1% of the survivors changed org.
deleted = set()
while len(deleted) < N * 3 // 100:
    start = rnd.randrange(N); length = rnd.choice([1, 1, 1, 2, 5, 20, 50, 200])
    deleted.update(range(start, min(N, start + length)))
changed = set(rnd.sample(range(N), N // 1000))
new_nodes = N // 10   # work done on live after the backup: ids N .. N+new_nodes-1

with open("backup-import/nodes.csv", "w") as b, open("live-import/nodes.csv", "w") as l:
    head = "id:ID,:LABEL,org_id:long,ref\n"
    b.write(head); l.write(head)
    for i in range(N):
        b.write(f"{i},{label(i)},{org(i)},r{i}\n")
        if i not in deleted:
            o = org(i) + 1000 if i in changed else org(i)
            l.write(f"{i},{label(i)},{o},r{i}\n")
    for i in range(N, N + new_nodes):
        l.write(f"{i},Booking,{org(i)},new{i}\n")

with open("backup-import/rels.csv", "w") as b, open("live-import/rels.csv", "w") as l:
    head = ":START_ID,:END_ID,:TYPE\n"
    b.write(head); l.write(head)
    for _ in range(R):
        s = rnd.randrange(N); e = rnd.randrange(N); t = rnd.choice(TYPES)
        b.write(f"{s},{e},{t}\n")
        if s not in deleted and e not in deleted:
            l.write(f"{s},{e},{t}\n")
print("deleted", len(deleted), "changed", len(changed), "new", new_nodes)
