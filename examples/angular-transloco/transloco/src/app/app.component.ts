import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { TranslocoDirective, TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AdminPanelComponent } from './admin/admin-panel.component';

@Component({
  selector: 'app-root',
  imports: [TranslocoDirective, TranslocoPipe, AdminPanelComponent],
  templateUrl: './app.component.html',
})
export class AppComponent {
  private readonly transloco = inject(TranslocoService);
  protected readonly userName = 'Ada';

  constructor() {
    // The tab title follows the active language: `selectTranslate` emits again on every change.
    const title = inject(Title);
    this.transloco.selectTranslate('home.title')
      .pipe(takeUntilDestroyed())
      .subscribe((text: string) => title.setTitle(text));
  }

  protected toggleLanguage(): void {
    this.transloco.setActiveLang(this.transloco.getActiveLang() === 'fr' ? 'en' : 'fr');
  }
}
