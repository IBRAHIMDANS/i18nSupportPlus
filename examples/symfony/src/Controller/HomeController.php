<?php

namespace App\Controller;

use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Translation\TranslatableMessage;
use Symfony\Contracts\Translation\TranslatorInterface;
use function Symfony\Component\Translation\t;

class HomeController extends AbstractController
{
    public function __construct(private readonly TranslatorInterface $translator)
    {
    }

    public function index(): Response
    {
        // The `messages` domain, named or not.
        $title = $this->translator->trans('home.title');
        $greeting = $this->translator->trans('home.greeting', ['%name%' => 'Ada'], 'messages');

        // Another domain, third argument.
        $dashboard = $this->translator->trans('dashboard.title', [], 'admin');

        // An XLIFF catalogue: keyed by `resname`, or by the source text.
        $invalid = $this->translator->trans('contact.email.invalid', [], 'validators');
        $short = $this->translator->trans('This value is too short.', [], 'validators');

        // A message translated later: t() and TranslatableMessage.
        $flash = t('home.title');
        $later = new TranslatableMessage('dashboard.title', [], 'admin');

        // Reported: the key is in no catalogue of the domain.
        $missing = $this->translator->trans('home.subtitle');

        return $this->render('home.html.twig', compact('title', 'greeting', 'dashboard', 'invalid', 'short', 'flash', 'later', 'missing'));
    }
}
