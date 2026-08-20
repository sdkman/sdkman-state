-- Hide the Oracle GraalVM rows that carry a '+r<N>' runtime-target build-metadata
-- section. That suffix records the GraalVM *product* version, not the JDK version,
-- so '25.2.4+r25' is not a newer build of '25.0.4' — it is a mis-versioned row.
--
-- This runs before V18 on purpose: left visible, '25.2.4+r25' would be read as the
-- head of its series and would retire the correct '25.0.4' row.
--
-- Hidden, not rewritten: the correct identifier cannot be derived from the version
-- string, and '25.0.4' is present and stays visible on every platform that carries
-- a '+r<N>' row, so GraalVM Oracle keeps advertising its major lines. DISCO is
-- dropping the suffix upstream, after which correctly-versioned rows supersede
-- these through the normal write-time rule.
--
-- The match is deliberately narrow: candidate 'java', distribution 'GRAALVM', and a
-- build-metadata section that starts with 'r' followed by digits. The legacy dot
-- spelling ('25.0.4.r25') is ineligible for a series and is left alone.

UPDATE versions
   SET visible = false
 WHERE candidate = 'java'
   AND distribution = 'GRAALVM'
   AND visible = true
   AND version ~ '\+r[0-9]+';
