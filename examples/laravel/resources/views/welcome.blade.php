<!DOCTYPE html>
<html lang="{{ str_replace('_', '-', app()->getLocale()) }}">
<head>
    <meta charset="utf-8">
    <title>{{ __('messages.welcome') }}</title>
</head>
<body>
    @include('partials.nav')

    <h1>{{ __('messages.welcome') }}</h1>
    <p>{{ __('messages.greeting', ['name' => 'Ada']) }}</p>
    <p>{!! trans('messages.auth.failed') !!}</p>
    <p>@lang('messages.auth.throttle', ['seconds' => 30])</p>
    <p>{{ __('Free shipping') }}</p>
    <p>@lang('messages.only_in_english')</p>

    {{-- Unknown on purpose: reported as an unresolved key. --}}
    <p>{{ __('messages.not_in_any_file') }}</p>
</body>
</html>
