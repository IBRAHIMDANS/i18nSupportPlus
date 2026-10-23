import createMiddleware from 'next-intl/middleware';
import { routing } from './i18n/routing';

export default createMiddleware(routing);

export const config = {
  // Every path but the API, Next.js internals and files with an extension.
  matcher: '/((?!api|trpc|_next|_vercel|.*\\..*).*)'
};
