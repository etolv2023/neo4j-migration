# Benchmark and ID-import spike (sandbox only)

Everything here runs against throw-away Docker containers. Nothing connects to a real server.

## Synthetic data

    python3 gen_synthetic.py 3000000      # writes backup-import/ and live-import/ CSV files in the current directory

The "backup" has N nodes (ids 0..N-1) and 2N relationships. The "live" copy lost 3% of the ids in runs of 1-200,
3 per thousand of the survivors changed organisation, and it has N/10 new nodes after the last backup id.

## Build the two databases with their ids kept

    for side in backup live; do
      docker run --rm -v $PWD/$side-data:/data -v $PWD/$side-import:/import neo4j:5.26.29-community \
        neo4j-admin database import full --id-type=actual --nodes=/import/nodes.csv \
        --relationships=/import/rels.csv --overwrite-destination neo4j
    done

Then start a server on each data directory (`-e NEO4J_AUTH=none -v $PWD/$side-data:/data`) and run
`neo4j-migration-cli.jar dry-run` against them.

## The spike

`--id-type=actual` was tried with sparse ids, unsorted input and a relationship whose end node does not exist.
Results are in `docs/RESULTS.md`.
