import { Component } from '@angular/core';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
})
export class AppComponent {
  name = 'Ada';

  // `$localize` with a custom id: resolved against the XLIFF files, like the template's markers.
  readonly saved = $localize`:@@cart.saved:Your cart was saved`;

  // A custom id no XLIFF file holds, on purpose: reported as unresolved.
  readonly unknown = $localize`:@@cart.doesNotExist:Not extracted yet`;

  // No custom id: Angular computes one from the text, so no key is read.
  readonly anonymous = $localize`Thank you`;

  greet(): string {
    return $localize`:Greeting shown in the header@@home.greeting:Hello ${this.name}:name:!`;
  }
}
