package strokeorder.model

import java.text.Normalizer

actual fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
