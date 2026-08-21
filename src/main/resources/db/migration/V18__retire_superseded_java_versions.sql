-- Retire the java rows that write-time supersession would have retired, had it
-- existed when they were published. The versions collection was carried across from
-- MongoDB verbatim, in its legacy spelling ('26.0.2', '26.0.2.fx'), and DISCO has
-- since been posting semverish builds of the same releases ('26.0.2+1.1',
-- '26.0.2-fx+1.1') without retiring what they replace. This one-off pass collapses
-- that accumulated duplication.
--
-- A release series is (candidate, distribution, platform, major, variant). Only the
-- eligible shapes take part in one: exactly three numeric core components, with an
-- optional 'fx'/'crac' variant in either the legacy dot spelling or the semverish
-- '-' spelling, and optional build metadata. Runtime targets ('25.0.4.r25'),
-- rebuild counters ('11.0.14.1') and two-component cores ('25.r25') are ineligible
-- and are left exactly as they are.
--
-- The backlog pass has no triggering POST, so unlike the write-time rule it cannot
-- be positional. It selects the survivor by ordering: highest core version wins,
-- and at equal core version a DISCO-generation row beats a migrated one. Generation
-- is read from the spelling — there is no provenance column — so a version carrying
-- a '-' or '+' section is DISCO and a bare '<major>.<minor>.<patch>' is migrated.
--
-- Two guards keep the pass conservative:
--
--   * A series with no DISCO row is untouched. Nothing has superseded those rows,
--     so they stay as they were — this is what keeps Corretto's major-8 line
--     advertising '8.0.504', '8.0.472' and '8.0.232'.
--   * A series whose two highest rows are both DISCO at the same core version is
--     skipped and reported. Separating them needs a semverish comparator, which is
--     deliberately unspecified. Those series resolve themselves when DISCO next
--     publishes into them and the write-time rule fires positionally.
--
-- Tags are left alone, exactly as the write-time rule leaves them. A retired row
-- still holding 'lts', 'latest' or a major alias keeps resolving through findByTag
-- until DISCO next hydrates that tag, so every such tag is reported rather than
-- moved — it is a short, self-correcting list that is worth seeing.
--
-- Runs after V17, which hides the mis-versioned GraalVM '+r<N>' rows. Left visible
-- those would be read as the head of their series and would retire the correct row.

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
