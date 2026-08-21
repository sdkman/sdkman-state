-- Retire the java rows that write-time supersession would have retired, had it existed
-- when they were published. See specs/java-version-supersession.md, 'Clearing the Backlog'.
--
-- The pass has no triggering POST, so it selects the survivor by ordering rather than
-- position: highest core version wins, and at equal core version a DISCO row — one whose
-- spelling carries a '-' or '+' section — beats a migrated one. A series holding no DISCO
-- row, and one whose two highest rows are both DISCO at the same core version, is left
-- untouched and reported. Runs after V17.

DO $$
DECLARE
    tied_series RECORD;
    stranded_tag RECORD;
    retired_count integer;
BEGIN
    CREATE TEMP TABLE v18_series ON COMMIT DROP AS
    WITH eligible AS (
        SELECT v.id,
               v.version,
               v.distribution,
               v.platform,
               core[1]::int AS major,
               core[2]::int AS minor,
               core[3]::int AS patch,
               coalesce(
                   substring(v.version from '^[0-9]+\.[0-9]+\.[0-9]+[.-](fx|crac)'),
                   ''
               ) AS variant,
               v.version ~ '[-+]' AS disco
          FROM versions v
          CROSS JOIN LATERAL (
              SELECT regexp_match(v.version, '^([0-9]+)\.([0-9]+)\.([0-9]+)') AS core
          ) c
         WHERE v.candidate = 'java'
           AND v.visible = true
           AND v.version ~ (
                  '^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'
               || '(?:\.(?:fx|crac)'
               || '|-(?:fx|crac)(?:\+[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*)?'
               || '|\+[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*'
               || ')?$'
               )
    ),
    ranked AS (
        SELECT e.*,
               row_number() OVER w AS rank_in_series,
               bool_or(e.disco) OVER (
                   PARTITION BY e.distribution, e.platform, e.major, e.variant
               ) AS has_disco,
               lead(e.disco) OVER w AS next_disco,
               lead(e.minor) OVER w AS next_minor,
               lead(e.patch) OVER w AS next_patch
          FROM eligible e
        WINDOW w AS (
            PARTITION BY e.distribution, e.platform, e.major, e.variant
            ORDER BY e.major DESC, e.minor DESC, e.patch DESC, e.disco DESC
        )
    )
    SELECT r.id,
           r.version,
           r.distribution,
           r.platform,
           r.major,
           r.variant,
           r.rank_in_series,
           r.has_disco,
           coalesce(
               bool_or(
                   r.rank_in_series = 1
                   AND r.disco
                   AND r.next_disco
                   AND r.next_minor = r.minor
                   AND r.next_patch = r.patch
               ) OVER (PARTITION BY r.distribution, r.platform, r.major, r.variant),
               false
           ) AS tied
      FROM ranked r;

    FOR tied_series IN
        SELECT distribution,
               platform,
               major,
               variant,
               string_agg(version, ', ' ORDER BY rank_in_series) AS versions
          FROM v18_series
         WHERE tied
         GROUP BY distribution, platform, major, variant
         ORDER BY distribution, platform, major, variant
    LOOP
        RAISE NOTICE
            'V18: skipping tied series distribution=% platform=% major=% variant=% (%)',
            coalesce(tied_series.distribution, '<none>'),
            tied_series.platform,
            tied_series.major,
            coalesce(nullif(tied_series.variant, ''), '<none>'),
            tied_series.versions;
    END LOOP;

    UPDATE versions v
       SET visible = false
      FROM v18_series s
     WHERE v.id = s.id
       AND s.has_disco
       AND NOT s.tied
       AND s.rank_in_series > 1;

    GET DIAGNOSTICS retired_count = ROW_COUNT;
    RAISE NOTICE 'V18: retired % superseded java version(s)', retired_count;

    FOR stranded_tag IN
        SELECT t.tag, s.version, s.distribution, s.platform
          FROM version_tags t
          JOIN v18_series s ON s.id = t.version_id
         WHERE s.has_disco
           AND NOT s.tied
           AND s.rank_in_series > 1
         ORDER BY t.tag, s.platform
    LOOP
        RAISE NOTICE
            'V18: tag % still points at retired version % (distribution=% platform=%)',
            stranded_tag.tag,
            stranded_tag.version,
            coalesce(stranded_tag.distribution, '<none>'),
            stranded_tag.platform;
    END LOOP;

    DROP TABLE v18_series;
END $$;
