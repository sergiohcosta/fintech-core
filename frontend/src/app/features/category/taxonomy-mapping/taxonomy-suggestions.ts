export interface TaxonomyRoot {
  code: string;
  label: string;
  icon: string;
}

export const TAXONOMY_ROOTS: TaxonomyRoot[] = [
  { code: 'INCOME',        label: 'Renda',              icon: 'attach_money'    },
  { code: 'HOUSING',       label: 'Moradia',            icon: 'home'            },
  { code: 'FOOD',          label: 'Alimentação',        icon: 'restaurant'      },
  { code: 'TRANSPORT',     label: 'Transporte',         icon: 'directions_car'  },
  { code: 'HEALTH',        label: 'Saúde',              icon: 'favorite'        },
  { code: 'LEISURE',       label: 'Lazer',              icon: 'movie'           },
  { code: 'EDUCATION',     label: 'Educação',           icon: 'school'          },
  { code: 'CLOTHING',      label: 'Vestuário',          icon: 'checkroom'       },
  { code: 'HOME_GOODS',    label: 'Casa & Decoração',   icon: 'weekend'         },
  { code: 'SUBSCRIPTIONS', label: 'Assinaturas',        icon: 'subscriptions'   },
  { code: 'PERSONAL_CARE', label: 'Cuidados Pessoais',  icon: 'spa'             },
  { code: 'PETS',          label: 'Pets',               icon: 'pets'            },
  { code: 'FINANCIAL',     label: 'Financeiro',         icon: 'account_balance' },
  { code: 'GIFTS',         label: 'Presentes e Doações',icon: 'card_giftcard'   },
];

// Mapeamento PT-BR → código. Chave sempre normalizada (sem acentos, lowercase).
const SUGGESTIONS: Record<string, string> = {
  'renda':             'INCOME',
  'receitas':          'INCOME',
  'salario':           'INCOME',
  'moradia':           'HOUSING',
  'casa':              'HOUSING',
  'alimentacao':       'FOOD',
  'comida':            'FOOD',
  'transporte':        'TRANSPORT',
  'saude':             'HEALTH',
  'lazer':             'LEISURE',
  'entretenimento':    'LEISURE',
  'educacao':          'EDUCATION',
  'roupas':            'CLOTHING',
  'vestuario':         'CLOTHING',
  'assinaturas':       'SUBSCRIPTIONS',
  'cuidados pessoais': 'PERSONAL_CARE',
  'beleza':            'PERSONAL_CARE',
  'pets':              'PETS',
  'animais':           'PETS',
  'financeiro':        'FINANCIAL',
  'financas':          'FINANCIAL',
  'presentes':         'GIFTS',
  'doacoes':           'GIFTS',
};

// NFD decompõe acentos compostos; replace remove combining diacritics (U+0300–U+036F)
function normalize(name: string): string {
  return name.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}

export function getSuggestion(name: string): string | null {
  if (!name) return null;
  return SUGGESTIONS[normalize(name)] ?? null;
}
