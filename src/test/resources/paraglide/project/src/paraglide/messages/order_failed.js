/* eslint-disable */
import { getLocale, experimentalStaticLocale } from '../runtime.js';

/** @typedef {import('../runtime.js').LocalizedString} LocalizedString */

/** @typedef {{}} Order_FailedInputs */



/**
* | output |
* | --- |
* | "Your order failed" |
*
* @param {Order_FailedInputs} inputs
* @param {{ locale?: "en" | "fr" }} options
* @returns {LocalizedString}
*/
export const order_failed = /** @type {((inputs?: Order_FailedInputs, options?: { locale?: "en" | "fr" }) => LocalizedString) & import('../runtime.js').MessageMetadata<Order_FailedInputs, { locale?: "en" | "fr" }, {}>} */ ((inputs = {}, options = {}) => {
	void inputs;
	const locale = experimentalStaticLocale ?? options.locale ?? getLocale()
	if (locale === "fr") return /** @type {LocalizedString} */ ("Votre commande a échoué")
	return /** @type {LocalizedString} */ ("Your order failed")
});