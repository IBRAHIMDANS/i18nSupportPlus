/* eslint-disable */
import { getLocale, experimentalStaticLocale } from '../runtime.js';

/** @typedef {import('../runtime.js').LocalizedString} LocalizedString */

/** @typedef {{}} Only_In_EnglishInputs */

const en_only_in_english = /** @type {(inputs: Only_In_EnglishInputs) => LocalizedString} */ () => {
	return /** @type {LocalizedString} */ (`Not translated yet`)
};

/** @type {(inputs: Only_In_EnglishInputs) => LocalizedString} */
const fr_only_in_english = en_only_in_english;

/**
* | output |
* | --- |
* | "Not translated yet" |
*
* @param {Only_In_EnglishInputs} inputs
* @param {{ locale?: "en" | "fr" }} options
* @returns {LocalizedString}
*/
export const only_in_english = /** @type {((inputs?: Only_In_EnglishInputs, options?: { locale?: "en" | "fr" }) => LocalizedString) & import('../runtime.js').MessageMetadata<Only_In_EnglishInputs, { locale?: "en" | "fr" }, {}>} */ ((inputs = {}, options = {}) => {
	const locale = experimentalStaticLocale ?? options.locale ?? getLocale()
	if (locale === "fr") return fr_only_in_english(inputs)
	return /** @type {LocalizedString} */ ("Not translated yet")
});