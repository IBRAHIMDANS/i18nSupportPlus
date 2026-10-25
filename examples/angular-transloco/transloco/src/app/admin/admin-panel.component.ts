import { Component, inject, signal } from '@angular/core';
import { provideTranslocoScope, TranslocoDirective, TranslocoService, translateSignal } from '@jsverse/transloco';

/** Reads the scope `admin`: `assets/i18n/admin/<lang>.json`, whose keys the code writes `admin.…`. */
@Component({
  selector: 'app-admin-panel',
  imports: [TranslocoDirective],
  providers: [provideTranslocoScope('admin')],
  templateUrl: './admin-panel.component.html',
})
export class AdminPanelComponent {
  private readonly transloco = inject(TranslocoService);
  protected readonly userName = 'Ada';
  protected readonly userCount = 3;
  protected readonly status = signal('');
  /** The standalone function, called in the injection context of a field: follows the active language. */
  protected readonly heading = translateSignal('admin.title');

  protected save(): void {
    this.status.set(this.transloco.translate('admin.saved', { name: this.userName }));
  }

  /** On purpose: `admin.saved` reads `{{name}}`, and this call passes nothing. */
  protected saveAnonymously(): void {
    this.status.set(this.transloco.translate('admin.saved'));
  }
}
