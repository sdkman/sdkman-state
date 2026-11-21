-- Retag every non-java 'lts' as 'stable', so no default moves when the cascade stops reading
-- 'lts'. See specs/022-non_java_version_rules.md, 'Rollout'.
--
-- An 'lts' is skipped when its scope already holds 'stable', and when it is a UNIVERSAL 'lts'
-- with no distribution while its candidate holds a distribution-less 'stable' at LINUX_X64,
-- since retagging it would move that default. Java keeps 'lts'.

UPDATE version_tags lts
   SET tag = 'stable',
       last_updated_at = NOW()
 WHERE lts.candidate <> 'java'
   AND lts.tag = 'lts'
   AND NOT EXISTS (
           SELECT 1
             FROM version_tags stable
            WHERE stable.candidate = lts.candidate
              AND stable.tag = 'stable'
              AND stable.platform = lts.platform
              AND stable.distribution IS NOT DISTINCT FROM lts.distribution
       )
   AND NOT (
           lts.platform = 'UNIVERSAL'
           AND lts.distribution IS NULL
           AND EXISTS (
                   SELECT 1
                     FROM version_tags stable
                    WHERE stable.candidate = lts.candidate
                      AND stable.tag = 'stable'
                      AND stable.platform = 'LINUX_X64'
                      AND stable.distribution IS NULL
               )
       );
