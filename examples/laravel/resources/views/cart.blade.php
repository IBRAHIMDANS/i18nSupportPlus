<section>
    <h2>{{ __('Log in') }}</h2>
    <p>{{ trans_choice('messages.cart.items', $count) }}</p>
    <p>@choice('messages.cart.items', $count)</p>
    <p>{{ __('courier::messages.sent') }}</p>

    {{-- Unknown on purpose, through the directive. --}}
    <p>@choice('messages.cart.lines', $count)</p>
</section>
