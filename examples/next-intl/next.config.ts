import type { NextConfig } from 'next';
import createNextIntlPlugin from 'next-intl/plugin';

// Reads the request configuration from ./i18n/request.ts.
const withNextIntl = createNextIntlPlugin();

const nextConfig: NextConfig = {};

export default withNextIntl(nextConfig);
