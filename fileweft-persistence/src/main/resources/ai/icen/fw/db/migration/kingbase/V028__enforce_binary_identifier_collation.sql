-- Version alignment with the MySQL security hardening migration. KingbaseES's
-- identifier equality remains case- and accent-sensitive under FileWeft's
-- existing varchar comparison contract, so no schema rewrite is required.
SELECT 1;
