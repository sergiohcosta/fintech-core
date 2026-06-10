package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.category.CategoryTemplateNode;
import com.fintech.api.domain.category.CategoryTaxonomy;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.fintech.api.domain.category.CategoryTaxonomy.*;

@Service
@RequiredArgsConstructor
public class DefaultCategorySeeder {

    private final CategoryRepository categoryRepository;

    private static final List<CategoryTemplateNode> TEMPLATE = List.of(
        new CategoryTemplateNode("Renda", "attach_money", "#26A69A", INCOME, List.of(
            new CategoryTemplateNode("Salário",      "payments",          "#26A69A", INCOME_SALARY),
            new CategoryTemplateNode("Freelance",    "work",              "#26A69A", INCOME_FREELANCE),
            new CategoryTemplateNode("Rendimentos",  "trending_up",       "#26A69A", INCOME_INVESTMENT_RETURNS),
            new CategoryTemplateNode("Reembolso",    "currency_exchange", "#26A69A", INCOME_REIMBURSEMENT)
        )),
        new CategoryTemplateNode("Moradia", "home", "#5C6BC0", HOUSING, List.of(
            new CategoryTemplateNode("Aluguel/Prestação", "home",       "#5C6BC0", HOUSING_RENT),
            new CategoryTemplateNode("Condomínio",        "apartment",  "#5C6BC0", HOUSING_CONDO),
            new CategoryTemplateNode("Energia",           "bolt",       "#5C6BC0", HOUSING_ENERGY),
            new CategoryTemplateNode("Água e Gás",        "water_drop", "#5C6BC0", HOUSING_WATER_GAS),
            new CategoryTemplateNode("Internet/TV",       "wifi",       "#5C6BC0", HOUSING_INTERNET)
        )),
        new CategoryTemplateNode("Alimentação", "restaurant", "#66BB6A", FOOD, List.of(
            new CategoryTemplateNode("Supermercado",  "shopping_cart",   "#66BB6A", FOOD_GROCERY),
            new CategoryTemplateNode("Restaurante",   "restaurant",      "#66BB6A", FOOD_RESTAURANT),
            new CategoryTemplateNode("Delivery",      "delivery_dining", "#66BB6A", FOOD_DELIVERY),
            new CategoryTemplateNode("Padaria/Café",  "coffee",          "#66BB6A", FOOD_BAKERY)
        )),
        new CategoryTemplateNode("Transporte", "directions_car", "#FFA726", TRANSPORT, List.of(
            new CategoryTemplateNode("Combustível",          "local_gas_station", "#FFA726", TRANSPORT_FUEL),
            new CategoryTemplateNode("Uber/Apps",            "local_taxi",        "#FFA726", TRANSPORT_RIDESHARE),
            new CategoryTemplateNode("Transporte Público",   "directions_bus",    "#FFA726", TRANSPORT_PUBLIC),
            new CategoryTemplateNode("IPVA/Seguro Auto",     "receipt_long",      "#FFA726", TRANSPORT_VEHICLE_TAX),
            new CategoryTemplateNode("Manutenção do Veículo","build",             "#FFA726", TRANSPORT_VEHICLE_MAINTENANCE)
        )),
        new CategoryTemplateNode("Saúde", "favorite", "#EF5350", HEALTH, List.of(
            new CategoryTemplateNode("Farmácia",          "local_pharmacy",    "#EF5350", HEALTH_PHARMACY),
            new CategoryTemplateNode("Plano de Saúde",    "health_and_safety", "#EF5350", HEALTH_INSURANCE),
            new CategoryTemplateNode("Consultas e Exames","medical_services",  "#EF5350", HEALTH_APPOINTMENTS),
            new CategoryTemplateNode("Academia e Esporte","fitness_center",    "#EF5350", HEALTH_FITNESS),
            new CategoryTemplateNode("Saúde Mental",      "psychology",        "#EF5350", HEALTH_MENTAL)
        )),
        new CategoryTemplateNode("Lazer", "movie", "#AB47BC", LEISURE, List.of(
            new CategoryTemplateNode("Cinema e Shows", "theaters",      "#AB47BC", LEISURE_ENTERTAINMENT),
            new CategoryTemplateNode("Viagens",        "flight",        "#AB47BC", LEISURE_TRAVEL),
            new CategoryTemplateNode("Hobbies",        "palette",       "#AB47BC", LEISURE_HOBBIES),
            new CategoryTemplateNode("Bares e Baladas","nightlife",     "#AB47BC", LEISURE_NIGHTLIFE)
        )),
        new CategoryTemplateNode("Educação", "school", "#26C6DA", EDUCATION, List.of(
            new CategoryTemplateNode("Cursos e Treinamentos", "computer",  "#26C6DA", EDUCATION_COURSES),
            new CategoryTemplateNode("Livros e Material",     "menu_book", "#26C6DA", EDUCATION_BOOKS),
            new CategoryTemplateNode("Escola/Faculdade",      "school",    "#26C6DA", EDUCATION_SCHOOL)
        )),
        new CategoryTemplateNode("Vestuário", "checkroom", "#8D6E63", CLOTHING, List.of(
            new CategoryTemplateNode("Roupas e Calçados",     "checkroom",             "#8D6E63", CLOTHING_APPAREL),
            new CategoryTemplateNode("Acessórios",            "watch",                 "#8D6E63", CLOTHING_ACCESSORIES),
            new CategoryTemplateNode("Lavanderia/Tinturaria", "local_laundry_service", "#8D6E63", CLOTHING_LAUNDRY)
        )),
        new CategoryTemplateNode("Casa & Decoração", "weekend", "#A1887F", HOME_GOODS, List.of(
            new CategoryTemplateNode("Móveis e Decoração",    "weekend",  "#A1887F", HOME_GOODS_FURNITURE),
            new CategoryTemplateNode("Utilidades Domésticas", "kitchen",  "#A1887F", HOME_GOODS_UTILITIES),
            new CategoryTemplateNode("Manutenção e Reparos",  "handyman", "#A1887F", HOME_GOODS_MAINTENANCE)
        )),
        new CategoryTemplateNode("Assinaturas", "subscriptions", "#BDBDBD", SUBSCRIPTIONS, List.of(
            new CategoryTemplateNode("Streaming de Vídeo",  "play_circle",   "#BDBDBD", SUBSCRIPTIONS_VIDEO),
            new CategoryTemplateNode("Streaming de Música", "music_note",    "#BDBDBD", SUBSCRIPTIONS_MUSIC),
            new CategoryTemplateNode("Games",               "sports_esports","#BDBDBD", SUBSCRIPTIONS_GAMING),
            new CategoryTemplateNode("Apps e Software",     "smartphone",    "#BDBDBD", SUBSCRIPTIONS_SOFTWARE)
        )),
        new CategoryTemplateNode("Cuidados Pessoais", "spa", "#F48FB1", PERSONAL_CARE, List.of(
            new CategoryTemplateNode("Cabelo e Beleza",   "content_cut", "#F48FB1", PERSONAL_CARE_BEAUTY),
            new CategoryTemplateNode("Higiene e Cuidados","soap",        "#F48FB1", PERSONAL_CARE_HYGIENE),
            new CategoryTemplateNode("Bem-estar e Spa",   "spa",         "#F48FB1", PERSONAL_CARE_WELLNESS)
        )),
        new CategoryTemplateNode("Pets", "pets", "#FFCA28", PETS, List.of(
            new CategoryTemplateNode("Ração e Petshop", "pets",            "#FFCA28", PETS_FOOD),
            new CategoryTemplateNode("Veterinário",     "medical_services","#FFCA28", PETS_VET),
            new CategoryTemplateNode("Banho e Tosa",    "shower",          "#FFCA28", PETS_GROOMING)
        )),
        new CategoryTemplateNode("Financeiro", "account_balance", "#78909C", FINANCIAL, List.of(
            new CategoryTemplateNode("Impostos e Taxas",          "receipt_long",   "#78909C", FINANCIAL_TAXES),
            new CategoryTemplateNode("Tarifas Bancárias",         "account_balance","#78909C", FINANCIAL_BANK_FEES),
            new CategoryTemplateNode("Empréstimos/Financiamentos","trending_down",  "#78909C", FINANCIAL_LOANS),
            new CategoryTemplateNode("Seguros",                   "shield",         "#78909C", FINANCIAL_INSURANCE)
        )),
        new CategoryTemplateNode("Presentes e Doações", "card_giftcard", "#EF9A9A", GIFTS, List.of(
            new CategoryTemplateNode("Presentes", "card_giftcard",      "#EF9A9A", GIFTS_PRESENTS),
            new CategoryTemplateNode("Doações",   "volunteer_activism", "#EF9A9A", GIFTS_DONATIONS)
        ))
    );

    public void seedForTenant(Tenant tenant) {
        List<Category> toSave = new ArrayList<>();
        for (CategoryTemplateNode rootNode : TEMPLATE) {
            Category root = buildCategory(rootNode, null, tenant);
            toSave.add(root);
            collectChildren(root, rootNode, tenant, toSave);
        }
        categoryRepository.saveAll(toSave);
    }

    private Category buildCategory(CategoryTemplateNode node, Category parent, Tenant tenant) {
        return Category.builder()
                .name(node.name())
                .icon(node.icon())
                .color(node.color())
                .taxonomyCode(node.taxonomy().name())
                .tenant(tenant)
                .parent(parent)
                .build();
    }

    private void collectChildren(Category parent, CategoryTemplateNode node, Tenant tenant, List<Category> acc) {
        for (CategoryTemplateNode child : node.children()) {
            Category childCategory = buildCategory(child, parent, tenant);
            acc.add(childCategory);
            collectChildren(childCategory, child, tenant, acc);
        }
    }
}
