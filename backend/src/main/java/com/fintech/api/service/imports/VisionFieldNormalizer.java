package com.fintech.api.service.imports;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pós-processamento DETERMINÍSTICO da saída crua do modelo de visão (#241 — endurecimento da
 * extração local com {@code qwen3-vl:8b-instruct}).
 *
 * <p><b>Por que existe:</b> pedir vocabulário fechado no prompt melhora a adesão, mas é
 * instrução, não restrição — o PoC de 70 docs devolveu {@code paymentMethod} em texto livre
 * ("Cartao de Credito") e a data em {@code dd/mm/aaaa}, formatos que derrubam a métrica de
 * "exato" e não batem com o vocabulário que o resto do pipeline espera. Aqui a garantia é de
 * CÓDIGO: funções puras (sem estado, sem IO, sem Spring), que qualquer teste cobre sem mock.
 * É a metade determinística do "prompt fechado + pós-processamento" do #241.
 *
 * <p><b>Contrato:</b> devolve o valor normalizado quando RECONHECE a entrada; devolve
 * {@code null} quando não reconhece — não adivinha e nunca devolve a entrada cru. O que fazer
 * com {@code null} (no {@link VisionExtractor}: preservar o valor original com confiança 0.0,
 * para o revisor ver o que a imagem devolveu) é decisão do chamador, não desta classe.
 */
public final class VisionFieldNormalizer {

    // Um único padrão cobre dd/MM/yyyy, d/M/yyyy, dd.MM.yy e dd-MM-yyyy: o que muda entre esses
    // formatos é só o separador (livre entre / . e -) e a largura de dia/mês — casar por GRUPOS
    // é mais simples (e menos sujeito a bug de ordem) do que quatro DateTimeFormatter.
    // O ISO (ano-primeiro) tem a ordem dos campos invertida, por isso é um padrão à parte.
    private static final Pattern YEAR_FIRST = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern DAY_FIRST = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4}|\\d{2})$");

    // TED/DOC são códigos de 3 letras: casados como TOKEN inteiro (\b), nunca como substring —
    // "documento"/"creditado" conteriam "doc"/"ted" e virariam "transferencia" por acidente.
    private static final Pattern TOKEN_TED = Pattern.compile("\\bted\\b");
    private static final Pattern TOKEN_DOC = Pattern.compile("\\bdoc\\b");

    private VisionFieldNormalizer() {
        // Classe de função pura: só métodos estáticos, instanciar não faz sentido.
    }

    /**
     * Converte a data lida pelo modelo para ISO {@code yyyy-MM-dd}. Aceita {@code dd/MM/yyyy},
     * {@code d/M/yyyy}, {@code dd.MM.yy}, {@code dd-MM-yyyy} e o próprio ISO — formatos que o
     * PoC mostrou o modelo emitir apesar do prompt pedir ISO. Ano de 2 dígitos é interpretado
     * como 20xx (o comprovante é do século XXI; "26" = 2026, não 1926).
     *
     * <p>Devolve {@code null} quando nenhum formato casa OU quando a data é IMPOSSÍVEL
     * (31/02, 13/13, 12/31/2026): o regex só valida a forma, o {@link LocalDate#of(int, int, int)}
     * valida o calendário — por isso a validação final é sempre via java.time, nunca via regex.
     */
    public static String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();

        Matcher yearFirst = YEAR_FIRST.matcher(value);
        if (yearFirst.matches()) {
            return toIso(
                    Integer.parseInt(yearFirst.group(1)),
                    Integer.parseInt(yearFirst.group(2)),
                    Integer.parseInt(yearFirst.group(3)));
        }

        Matcher dayFirst = DAY_FIRST.matcher(value);
        if (dayFirst.matches()) {
            int year = Integer.parseInt(dayFirst.group(3));
            if (year < 100) {
                year += 2000;  // ano de 2 dígitos → 20xx
            }
            return toIso(year, Integer.parseInt(dayFirst.group(2)), Integer.parseInt(dayFirst.group(1)));
        }

        return null;
    }

    /**
     * Mapeia texto livre devolvido pelo modelo para o vocabulário fechado
     * {@code pix|credito|debito|dinheiro|boleto|transferencia}. Caixa e acentos são ignorados
     * (NFD + remoção das marcas combinantes) porque o modelo oscila entre "Crédito", "CRÉDITO"
     * e "credito" — e "Numerário"/"Espécie" são sinônimos BR de dinheiro que aparecem em
     * comprovante. Reconhece por RAIZ da palavra (ex.: "cartão de crédito" → "credito") porque
     * o texto vem com prefixos/sufixos de ruído ("Pagamento aprovado - Cartao de Credito").
     *
     * <p>Desconhecido (ex.: "cripto", "vale") → {@code null}: o vocabulário é FECHADO por
     * decisão do #241, então o que não pertence a ele não é chutado para o valor mais próximo.
     */
    public static String normalizePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = fold(raw);

        // Ordem livre: as raízes são disjuntas ("credito" não contém "debito" nem vice-versa);
        // só TED/DOC precisam de casamento por token, por serem códigos curtos.
        if (text.contains("pix")) {
            return "pix";
        }
        if (text.contains("transferencia") || TOKEN_TED.matcher(text).find() || TOKEN_DOC.matcher(text).find()) {
            return "transferencia";
        }
        if (text.contains("debito")) {
            return "debito";
        }
        if (text.contains("credito")) {
            return "credito";
        }
        if (text.contains("boleto")) {
            return "boleto";
        }
        if (text.contains("dinheiro") || text.contains("numerario") || text.contains("especie")) {
            return "dinheiro";
        }
        return null;
    }

    /**
     * Garante que {@code direction} saia como {@code debit} ou {@code credit} — as ÚNICAS
     * duas strings que o {@code ImportService} sabe interpretar como tipo da transação. Aceita
     * variações de caixa/acento e os equivalentes PT ("Crédito"/"DÉBITO"): basta a raiz
     * "credit"/"debit" estar presente ("credito" contém "credit").
     *
     * <p><b>Decisão (#241): desconhecido → {@code null}</b>, e NÃO mais "debit" como antes.
     * O default antigo FABRICAVA uma direção que o modelo não declarou — em campo de dinheiro,
     * direção errada é pior que direção ausente (virava EXPENSE silencioso). Com {@code null}
     * o staged deixa explícito que não há direção reconhecida e o revisor decide; quem insiste
     * no default continua tendo EXPENSE no commit (o {@code ImportService} trata tudo que não
     * for "credit" como despesa), só que sem mascarar a incerteza na tela de revisão.
     */
    public static String normalizeDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = fold(raw);
        if (text.contains("credit")) {
            return "credit";
        }
        if (text.contains("debit")) {
            return "debit";
        }
        return null;
    }

    /** Minúsculas + sem acentos + espaços colapsados — o "fold" que ignora ruído de escrita. */
    private static String fold(String raw) {
        String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** {@code null} quando o trio ano/mês/dia não forma uma data real (31/02, 13/13, ...). */
    private static String toIso(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day).toString();  // toString() é ISO yyyy-MM-dd
        } catch (DateTimeException e) {
            return null;
        }
    }
}
