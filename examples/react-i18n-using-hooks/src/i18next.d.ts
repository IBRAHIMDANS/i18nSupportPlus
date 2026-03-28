import 'i18next';

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation';
    resources: {
      translation: {
        title: string;
        description: {
          part1: string;
          part2: string;
          part3: string;
        };
      };
      hook: {
        example: {
          title: string;
        };
      };
      main: {
        header: {
          title: string;
        };
        trans: {
          description: string;
        };
      };
    };
  }
}
