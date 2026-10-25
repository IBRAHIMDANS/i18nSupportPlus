import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { TranslateDirective, TranslatePipe, TranslateService } from '@ngx-translate/core';

@Component({
  selector: 'app-root',
  imports: [TranslateDirective, TranslatePipe],
  templateUrl: './app.component.html',
})
export class AppComponent {
  private readonly translate = inject(TranslateService);
  protected readonly userName = 'Ada';
  protected readonly itemCount = 3;
  protected readonly status = signal('');

  constructor() {
    // The tab title follows the current language: `stream` emits again on every change.
    const title = inject(Title);
    this.translate.stream('home.title')
      .pipe(takeUntilDestroyed())
      .subscribe((text: string) => title.setTitle(text));
  }

  protected toggleLanguage(): void {
    this.translate.use(this.translate.getCurrentLang() === 'fr' ? 'en' : 'fr');
  }

  protected checkout(): void {
    this.translate.get('cart.summary', { count: this.itemCount })
      .subscribe((text: string) => this.status.set(text));
  }

  protected clearCart(): void {
    this.status.set(this.translate.instant('cart.cleared'));
  }

  /** On purpose: `home.greeting` reads `{{name}}`, and this call passes nothing. */
  protected greetAnonymously(): void {
    this.status.set(this.translate.instant('home.greeting'));
  }
}
