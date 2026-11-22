const defaultLabel = window.wp.i18n.__('Mobile Money & Card (ELEPAY)', 'woocommerce-elepay');
const decodeEntities = window.wp.htmlEntities.decodeEntities;

const settings = window.wc.wcSettings.getSetting('elepay_data', {});

const label = decodeEntities(settings.title || defaultLabel);
const Content = () => {
    return window.wp.element.createElement('span', null, decodeEntities(settings.description || ''));
};

const Block_Gateway = {
    name: 'elepay',
    label: label,
    content: window.wp.element.createElement(Content, null),
    edit: window.wp.element.createElement(Content, null),
    canMakePayment: () => true,
    ariaLabel: label,
    supports: {
        features: settings.supports || ['products'],
    },
};

window.wc.wcBlocksRegistry.registerPaymentMethod(Block_Gateway);
