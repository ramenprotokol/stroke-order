package strokeorder.model

/**
 * Unicode NFKC normalisation, from the platform (String.prototype.normalize in the
 * browser). It folds full-width Latin (ｍｉｚｕ) and half-width katakana (ｽｲ) into their
 * ordinary forms, so search treats them like any other input.
 */
expect fun nfkc(s: String): String
