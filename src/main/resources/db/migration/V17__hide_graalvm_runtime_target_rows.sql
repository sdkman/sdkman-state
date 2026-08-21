-- Hide the Oracle GraalVM rows whose '+r<N>' build metadata records the GraalVM product
-- version rather than the JDK version, so '25.2.4+r25' is not a newer build of '25.0.4'.
-- Runs before V18: left visible, such a row reads as the head of its series and retires
-- the correct one.

UPDATE versions
   SET visible = false
 WHERE candidate = 'java'
   AND distribution = 'GRAALVM'
   AND visible = true
   AND version ~ '\+r[0-9]+';
