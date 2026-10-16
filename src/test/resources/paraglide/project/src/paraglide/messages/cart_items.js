/* eslint-disable */
import * as registry from '../registry.js'
import { getLocale, experimentalStaticLocale } from '../runtime.js';

/** @typedef {import('../runtime.js').LocalizedString} LocalizedString */

/** @typedef {{ count: NonNullable<unknown> }} Cart_ItemsInputs */

const en_cart_items = /** @type {(inputs: Cart_ItemsInputs) => LocalizedString} */ (i) => {const countPlural = registry.plural("en", i?.count, {}, true);
	if (countPlural === "one") return /** @type {LocalizedString} */ (`There is one item in your cart.`);
	if (countPlural === "other") return /** @type {LocalizedString} */ (`There are ${i?.count} items in your cart.`);
	return /** @type {LocalizedString} */ ("cart_items");
};

const fr_cart_items = /** @type {(inputs: Cart_ItemsInputs) => LocalizedString} */ (i) => {const countPlural = registry.plural("fr", i?.count, {}, true);
	if (countPlural === "one") return /** @type {LocalizedString} */ (`Il y a un article dans votre panier.`);
	if (countPlural === "other") return /** @type {LocalizedString} */ (`Il y a ${i?.count} articles dans votre panier.`);
	return /** @type {LocalizedString} */ ("cart_items");
};

/**
* | countPlural | output |
* | --- | --- |
* | "one" | "There is one item in your cart." |
* | "other" | "There are {count} items in your cart." |
*
* @param {Cart_ItemsInputs} inputs
* @param {{ locale?: "en" | "fr" }} options
* @returns {LocalizedString}
*/
export const cart_items = /** @type {((inputs: Cart_ItemsInputs, options?: { locale?: "en" | "fr" }) => LocalizedString) & import('../runtime.js').MessageMetadata<Cart_ItemsInputs, { locale?: "en" | "fr" }, {}>} */ ((inputs, options = {}) => {
	const locale = experimentalStaticLocale ?? options.locale ?? getLocale()
	if (locale === "fr") return fr_cart_items(inputs)
	return en_cart_items(inputs)
});