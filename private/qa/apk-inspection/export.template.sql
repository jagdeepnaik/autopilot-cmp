-- Copy this file to export.sql, set the authorized SQLCipher key locally, then run it from this folder.
PRAGMA key='REPLACE_WITH_AUTHORIZED_KEY';
.headers on
.mode csv
.once ces.csv
SELECT * FROM CES ORDER BY _id;
.once cbt.csv
SELECT * FROM CBT ORDER BY _id;
.once questions.csv
SELECT * FROM QUESTIONS ORDER BY _id;
.once answers.csv
SELECT * FROM ANSWERS ORDER BY _id;
