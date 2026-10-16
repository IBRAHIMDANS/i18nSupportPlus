/* eslint-disable */
import { getLocale, experimentalStaticLocale } from '../runtime.js';

/** @typedef {import('../runtime.js').LocalizedString} LocalizedString */

/** @typedef {{ name: NonNullable<unknown> }} GreetingInputs */

const en_greeting = /** @type {(inputs: GreetingInputs) => LocalizedString} */ (i) => {
	return /** @type {LocalizedString} */ (`Hello ${i?.name}!`)
};

const fr_greeting = /** @type {(inputs: GreetingInputs) => LocalizedString} */ (i) => {
	return /** @type {LocalizedString} */ (`Bonjour ${i?.name} !`)
};

/**
* | output |
* | --- |
* | "Hello {name}!" |
*
* @param {GreetingInputs} inputs
* @param {{ locale?: "en" | "fr" }} options
* @returns {LocalizedString}
*/
export const greeting = /** @type {((inputs: GreetingInputs, options?: { locale?: "en" | "fr" }) => LocalizedString) & import('../runtime.js').MessageMetadata<GreetingInputs, { locale?: "en" | "fr" }, {}>} */ ((inputs, options = {}) => {
	const locale = experimentalStaticLocale ?? options.locale ?? getLocale()
	if (locale === "fr") return fr_greeting(inputs)
	return en_greeting(inputs)
});