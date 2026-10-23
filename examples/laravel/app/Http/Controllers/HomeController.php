<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Lang;

class HomeController
{
    public function index(Request $request)
    {
        return view('welcome', [
            'title' => __('messages.welcome'),
            'greeting' => __('messages.greeting', ['name' => 'Ada']),
            'error' => trans('messages.auth.failed'),
            'throttle' => Lang::get('messages.auth.throttle', ['seconds' => 30]),
            'partial' => __('messages.only_in_english'),
            'thanks' => __('Thanks for your order'),
            // Unknown on purpose: reported as an unresolved key.
            'missing' => __('messages.does_not_exist'),
            // A request parameter, not a translation: never reported.
            'page' => $request->get('page'),
        ]);
    }

    public function cart(Request $request)
    {
        $count = (int) $request->get('count', 0);

        return view('cart', [
            'summary' => trans_choice('messages.cart.items', $count),
            'summaryAgain' => app('translator')->choice('messages.cart.items', $count),
            'sent' => __('courier::messages.sent'),
            // The package ships no override for this file: neither resolved nor reported.
            'tracking' => __('courier::tracking.link'),
        ]);
    }
}
