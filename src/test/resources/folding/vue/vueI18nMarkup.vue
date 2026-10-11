<template>
    <i18n-t keypath=<fold text='Translation test en'>"test:ref.section.key"</fold> tag="p"><b>x</b></i18n-t>
    <I18nT keypath=<fold text='Lorem ipsum dolor si...'>"test:ref.section.longValue"</fold>></I18nT>
    <p v-t="<fold text='Translation test en'>'test:ref.section.key'</fold>"></p>
    <p v-t="{ path: <fold text='Translation test en'>'test:ref.section.key'</fold>, args: { label: 'not.a.key' } }"></p>
    <i18n-t :keypath="<fold text='Translation test en'>'test:ref.section.key'</fold>" tag="p"></i18n-t>
    <I18nT v-bind:keypath="<fold text='Translation test en'>'test:ref.section.key'</fold>"></I18nT>
    <i18n-t :keypath="keyVariable" tag="p"></i18n-t>
    <i18n-t keypath="test:ref.section.unresolved" tag="p"></i18n-t>
    <p v-t="'test:ref.section.unresolved'"></p>
    <my-comp keypath="test:ref.section.key"></my-comp>
    <h1>{{ <fold text='Translation test en'>$t('test:ref.section.key')</fold>}}</h1>
</template>
