/* eslint-disable */
import { getLocale, experimentalStaticLocale } from '../runtime.js';

/** @typedef {import('../runtime.js').LocalizedString} LocalizedString */

/** @typedef {{}} User_Profile_TitleInputs */



/**
* | output |
* | --- |
* | "User Profile" |
*
* @param {User_Profile_TitleInputs} inputs
* @param {{ locale?: "en" | "fr" }} options
* @returns {LocalizedString}
*/
const user_profile_title = /** @type {((inputs?: User_Profile_TitleInputs, options?: { locale?: "en" | "fr" }) => LocalizedString) & import('../runtime.js').MessageMetadata<User_Profile_TitleInputs, { locale?: "en" | "fr" }, {}>} */ ((inputs = {}, options = {}) => {
	void inputs;
	const locale = experimentalStaticLocale ?? options.locale ?? getLocale()
	if (locale === "fr") return /** @type {LocalizedString} */ ("Profil utilisateur")
	return /** @type {LocalizedString} */ ("User Profile")
});
export { user_profile_title as "user.profile.title" }