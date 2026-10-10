import { translate } from '../src/translate';

// A rule excludes `translate` under legacy/: this call is not a translation call.
export const old = () => translate('common:wrapped.excluded');
