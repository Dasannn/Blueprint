# PlayerStatus — Sistema de reputacion y estatus social

> Transcripcion automatica de `PlayerStatus-Sistema-Reputacion.docx` (fuente autoritativa, en la misma carpeta).
> Regenerar con `python .agent/docx2md.py <docx> <md>`.

PLAYERSTATUS

Sistema de reputacióny estatus social

Documento de diseño funcional para un sistema social de Minecraft basado en confianza, reputación y consecuencias ligeras.

| Objetivo del diseño Crear una sociedad emergente dentro del servidor: la reputación debe influir en cómo otros jugadores perciben, confían e interactúan con una persona, sin convertir el sistema en una ventaja de combate ni impedirle jugar. |

Principios clave

Las consecuencias son sociales, no buffs directos de daño, vida, minería o PvP.

La reputación negativa puede generar fricción y advertencias, pero no debe silenciar ni bloquear la experiencia básica.

Status social, confianza estadística y Killing Psychosis son métricas separadas.

Dar o quitar reputación tiene un coste real para reducir spam, venganzas y manipulación.

La información contextual importa: un número sin historial explica muy poco.

Versión conceptual 1.0

Contenido

| # | Sección |
| 1 | Visión y principios de diseño |
| 2 | Modelo de identidad social |
| 3 | Status social y prefixes |
| 4 | Reputation Confidence |
| 5 | Killing Psychosis |
| 6 | Economía de reputación |
| 7 | Controles antiabuso |
| 8 | GUI y experiencia de usuario |
| 9 | Comentarios públicos y filtro Bobba |
| 10 | Perfil social, chat y percepción |
| 11 | Trade warnings y oportunidades sociales |
| 12 | Vouching: confianza con riesgo |
| 13 | Integraciones y automatización |
| 14 | Modelo de datos sugerido |
| 15 | Comandos, permisos y configuración |
| 16 | Fases de implementación |
| 17 | Criterios de aceptación |

1. Visión y principios de diseño

PlayerStatus debe funcionar como una capa social del servidor. El objetivo no es decidir quién puede o no puede jugar, sino reflejar de forma visible cómo una comunidad percibe a una persona a partir de sus interacciones.

| Principio rector Un alto status social abre más oportunidades de confianza. Un bajo status social genera más fricción, menor visibilidad y advertencias contextuales, pero no elimina funciones esenciales. |

1.1 Lo que el sistema sí debe hacer

Hacer que la reputación sea visible en el chat, perfiles y determinadas interacciones.

Dar contexto sobre por qué una persona es considerada confiable o poco confiable.

Permitir que la comunidad construya una reputación de forma costosa, limitada y auditable.

Favorecer comportamientos cooperativos sin convertirlos en una obligación.

Permitir recuperación: un mal historial no debe convertirse automáticamente en una condena permanente.

1.2 Lo que conviene evitar

Bonificaciones de combate, daño, vida, velocidad, minería o drops por tener reputación alta.

Silencios automáticos o bloqueo total del chat por reputación baja.

Pérdidas automáticas de status ante eventos ambiguos, como romper un bloque sin saber si había permiso.

Permitir que una sola persona pueda destruir o inflar la reputación de otra con repetidas votaciones.

2. Modelo de identidad social

La propuesta se apoya en tres métricas independientes. Separarlas evita confundir “ser nuevo”, “ser poco confiable” y “ser agresivo”.

| Métrica | Qué representa | Ejemplo |
| Status social | Percepción de confianza y comportamiento social. | +72: jugador ampliamente respetado. |
| Reputation Confidence | Cuánta evidencia existe detrás del status. | Established: muchas personas distintas han participado. |
| Killing Psychosis | Tendencia a matar jugadores; no equivale a mala reputación. | Extreme: PvP frecuente, aunque podría seguir siendo confiable en trades. |

| Separación importante Un jugador puede tener Status +82 y Psychosis Extreme. También puede tener Status -71 y Psychosis Low si nunca mata, pero roba, estafa o grifea. |

3. Status social y prefixes

Los prefixes ya definidos por el plugin deben mantenerse como identidad visual. La nueva estructura debe mapear cada prefix a un rango de status y a consecuencias ligeras de percepción.

3.1 Gradiente de visibilidad en chat

| Rango conceptual | Color del mensaje | Efecto buscado |
| Status mínimo | #202020 (casi negro) | Mensaje visible, pero fácil de ignorar. |
| Bajo | Gris oscuro | Percepción de reputación dudosa. |
| Medio-bajo | Gris | Menor confianza inicial. |
| Neutral | Gris claro | Sin señal social destacable. |
| Alto | Blanco suave | Buena reputación y lectura clara. |
| Status máximo | Blanco brillante | Máxima claridad y reconocimiento social. |

Se recomienda evitar negro absoluto (#000000), ya que puede volverse ilegible según el cliente, fondo del chat o resource pack. Un casi-negro mantiene el concepto sin romper la accesibilidad.

3.2 Consecuencias recomendadas

| Área | Status alto | Status bajo |
| Chat | Mayor claridad visual. | Color más oscuro, sin ocultar mensajes. |
| Perfil | Indicador positivo y contexto de confianza. | Advertencias y acceso rápido a comentarios. |
| Trading | Señal de reputación establecida. | Advertencia previa antes de continuar. |
| Marketplace | Elegible para badges o funciones sociales opcionales. | Puede requerir confirmaciones adicionales. |
| Vouching | Mayor credibilidad si respalda a otro jugador. | Respaldo permitido, pero con menor peso o mayores límites. |

4. Reputation Confidence

Un jugador nuevo con status 0 no debería ser tratado igual que alguien con 300 horas de actividad, decenas de interacciones y status 0. Por eso se recomienda una métrica de confianza estadística separada.

| Nivel | Interpretación sugerida |
| Unknown | No hay información suficiente. |
| Low confidence | Pocas personas han participado; la reputación todavía puede ser volátil. |
| Established | Existe una muestra social razonable. |
| High confidence | La reputación se sostiene en muchas interacciones y jugadores distintos. |

La Confidence puede calcularse principalmente con el número de jugadores únicos que han emitido reputación, ponderado por antigüedad y diversidad de interacciones. Debe evitarse que muchas acciones del mismo usuario aumenten artificialmente la confianza.

5. Killing Psychosis

Killing Psychosis debe mantenerse como un sistema independiente dedicado a identificar a jugadores con una tendencia elevada a matar a otros jugadores. Su función es describir agresividad PvP, no moralidad ni confiabilidad general.

No debe reducir automáticamente el Status social por cada kill.

Puede mostrarse junto al perfil para que otros jugadores entiendan el riesgo PvP.

Puede utilizar ventanas temporales para distinguir PvP reciente de historial antiguo.

Los contextos consentidos —arenas, duelos o eventos— pueden excluirse si el servidor dispone de esa información.

6. Economía de reputación

Agregar o restar status debe tener un coste monetario. El objetivo no es vender reputación, sino hacer costoso emitir una valoración para que el jugador piense antes de usarla.

| Fórmula base coste = max(coste_mínimo, balance_actual × porcentaje) |

| Acción | Valor inicial sugerido | Motivo |
| Dar confianza (+1) | 0,50% del balance | Costo suficiente para frenar spam sin castigar demasiado. |
| Quitar confianza (-1) | 0,75% del balance | La acción negativa tiene mayor potencial de abuso. |
| Coste mínimo | Configurable | Evita que saldos extremadamente bajos conviertan la acción en gratuita. |

Los porcentajes son parámetros de diseño, no valores rígidos. Deben ser configurables desde el archivo de configuración y calibrarse según la economía real del servidor.

6.1 Coste progresivo anti-spam

Dentro de una ventana temporal, cada valoración adicional puede aumentar el multiplicador del coste. Ejemplo conceptual: 1.0x, 1.5x, 2.0x, 3.0x. El multiplicador se reinicia gradualmente.

6.2 Regla esencial

| El dinero compra el derecho a emitir una opinión, no el resultado. Un jugador rico no debería poder convertir dinero directamente en +100 de status. El cambio real debe depender de personas distintas y de reglas de frecuencia. |

7. Controles antiabuso

Un sistema de reputación comunitario debe asumir que existirán venganzas, alianzas coordinadas y cuentas utilizadas para manipular resultados. Los siguientes controles reducen esos riesgos sin volver el sistema excesivamente restrictivo.

| Control | Función |
| Cooldown por pareja | El mismo jugador no puede modificar repetidamente a la misma persona dentro de una ventana configurable. |
| Usuarios únicos | La reputación de 20 personas distintas pesa más que 20 acciones del mismo jugador. |
| Coste progresivo | Valorar muchas personas en poco tiempo se vuelve cada vez más caro. |
| Comentario obligatorio en negativo | Una valoración negativa debe incluir un motivo público. |
| Historial auditable | Las acciones deben guardar autor, objetivo, hora, coste y motivo. |
| Límites diarios | Opcionales por jugador para reducir campañas coordinadas. |
| Decay suave | Las valoraciones antiguas pueden perder peso gradualmente sin borrar el historial. |
| Protección de nuevos jugadores | Status neutral inicial y señal Unknown en lugar de asumir desconfianza. |

8. GUI y experiencia de usuario

La GUI debe convertir acciones sociales complejas en un flujo fácil de entender y difícil de ejecutar por accidente.

8.1 Pantalla principal del perfil

Cabeza y nombre del jugador.

Prefix actual y rango de status.

Barra o indicador de Status social.

Reputation Confidence.

Killing Psychosis.

Conteo resumido de experiencias positivas y negativas.

Botones Trust, Distrust, Ver comentarios y Vouch.

8.2 Flujo Trust / Distrust

1.  El jugador selecciona Trust o Distrust.

2.  La GUI calcula y muestra el coste exacto antes de confirmar.

3.  Si es Distrust, se solicita un comentario obligatorio.

4.  Se aplica el filtro Bobba antes de guardar.

5.  Se muestra una pantalla final de confirmación con acción, coste y objetivo.

6.  La transacción y el evento reputacional se guardan de forma atómica.

| Ejemplo de confirmación “Dar Distrust a Steve costará $327.50. Esta valoración será pública y quedará en su historial.” |

9. Comentarios públicos y filtro Bobba

Los comentarios convierten el status en información explicable. Una reputación de -40 sin contexto es difícil de interpretar; un historial con motivos permite a otros jugadores tomar decisiones informadas.

9.1 Reglas sugeridas

Comentario obligatorio para valoraciones negativas y opcional para positivas.

Longitud mínima y máxima configurable para evitar “-” o textos excesivos.

Comentarios visibles desde el perfil social del jugador.

Orden por relevancia o recencia, con paginación.

Staff puede ocultar contenido indebido sin borrar el evento reputacional subyacente.

9.2 Filtro Bobba

Cualquier palabra detectada como grosería se reemplaza por “bobba” en lugar de rechazar por completo el comentario. El filtro debería normalizar mayúsculas, separadores, sustituciones obvias de letras y puntuación para reducir evasiones simples.

| Entrada conceptual | Salida visible |
| “Eres un [grosería] estafador” | “Eres un bobba estafador” |
| “p.u.t... [texto]” | “bobba [texto]” |

10. Perfil social, chat y percepción

10.1 Hover del nombre

Al pasar el cursor por el nombre de un jugador en el chat, el plugin puede mostrar un resumen compacto:

| Ejemplo Social Status: RespectedReputation: +67Confidence: HighKilling Psychosis: Low42 jugadores han participado en esta reputación. |

10.2 Ejemplo de perfil completo

| Daniel  [RESPECTED] Social Status        ████████░░  +72Reputation Confidence █████████░  EstablishedKilling Psychosis     ██░░░░░░░░  LowFeedback: 👍 46   👎 7 |

El objetivo es que el perfil sea informativo de un vistazo y permita profundizar en el historial cuando la interacción tenga riesgo —por ejemplo, un trade importante—.

11. Trade warnings y oportunidades sociales

La reputación puede condicionar interacciones sin prohibirlas. El mejor ejemplo es el comercio entre jugadores.

| Advertencia por status bajo ⚠ Este jugador tiene reputación social baja.17 jugadores reportaron experiencias negativas.[Continuar] [Ver reputación] [Cancelar] |

| Señal por status alto ✓ Este jugador tiene una reputación positiva establecida.46 interacciones positivas registradas. |

11.1 Beneficios suaves para status alto

Badges sociales o indicadores de “Trusted”.

Mayor visibilidad en sistemas de anuncios o marketplace comunitario, si existen.

Menos confirmaciones de advertencia en interacciones sociales de bajo riesgo.

Mayor peso contextual cuando hace vouch por un jugador nuevo, sin multiplicadores extremos.

11.2 Limitantes suaves para status bajo

Mensajes más oscuros y menos llamativos.

Advertencias antes de trades u otras operaciones sociales relevantes.

Acceso visible a su historial de comentarios negativos.

Mayor necesidad de confirmación, no bloqueo absoluto.

12. Vouching: confianza con riesgo

Vouching permite que un jugador respalde públicamente a otro y deposite dinero como garantía. Esta mecánica convierte la reputación en una red social de confianza, especialmente útil para jugadores nuevos.

12.1 Flujo conceptual

1.  Daniel ejecuta /status vouch Steve o usa la GUI.

2.  Selecciona o acepta una garantía, por ejemplo $5,000.

3.  El dinero queda bloqueado mientras el vouch esté activo.

4.  El perfil de Steve muestra “Vouched by Daniel”.

5.  Si Steve acumula eventos negativos severos bajo reglas definidas, Daniel puede perder parte de la garantía.

6.  Si el vouch expira sin incidentes, la garantía se libera.

| Idea central “Steve acaba de entrar al servidor, todavía no tiene reputación, pero Daniel responde por él.” Esto es más expresivo que regalarle puntos de status. |

12.2 Salvaguardas

El vouch no debe transferir automáticamente el status del garante.

Debe existir un límite de vouches activos por persona.

Las condiciones de pérdida de garantía tienen que ser objetivas y configurables.

No se recomienda penalizar por una sola valoración negativa aislada.

13. Integraciones y automatización

El plugin puede integrarse con otras fuentes para ofrecer contexto, pero debe evitar castigos automáticos basados en señales ambiguas.

| Integración | Uso sugerido | Precaución |
| CoreProtect | Consultar historial ante reportes de grief. | No asumir que todo bloque roto fue grief. |
| Lands / Towny / GriefPrevention | Detectar permisos o pertenencia territorial. | Diferenciar acciones autorizadas. |
| Sistema de trades | Solicitar rating al finalizar un intercambio. | Una operación cancelada no equivale a fraude. |
| Economía / Vault | Cobrar Trust, Distrust y garantías. | Transacciones atómicas y rollback ante errores. |
| Duelos / arenas | Excluir kills consentidas de Psychosis si aplica. | Depende de la integración disponible. |

13.1 Eventos de reputación

Conviene exponer eventos internos o una pequeña API para que otros módulos puedan abrir el flujo de valoración tras una interacción verificable. Por ejemplo: trade_completed, contract_finished o support_given. La decisión final de valorar debe seguir siendo del jugador salvo que exista una regla objetiva y explícita.

14. Modelo de datos sugerido

El modelo de datos debe permitir recalcular reputación, auditar acciones y aplicar decay sin perder el historial.

| Entidad | Campos principales sugeridos |
| PlayerSocialProfile | uuid, current_status, confidence_level, psychosis_score, created_at, updated_at |
| ReputationEvent | id, actor_uuid, target_uuid, delta, type, cost, comment_id, created_at |
| ReputationComment | id, author_uuid, target_uuid, text_filtered, moderation_state, created_at |
| Vouch | id, guarantor_uuid, target_uuid, stake, state, starts_at, expires_at, loss_amount |
| Cooldown / RateLimit | actor_uuid, target_uuid, action_type, expires_at |
| AuditEvent | id, actor, operation, target, before, after, timestamp |

14.1 Recalculo versus valor acumulado

Es recomendable conservar eventos individuales y derivar el status agregado desde ellos, o al menos mantener ambos. Guardar únicamente un entero final dificulta investigar abuso, aplicar decay o corregir bugs históricos.

15. Comandos, permisos y configuración

15.1 Comandos orientativos

| Comando | Función |
| /status [jugador] | Abre el perfil social. |
| /status trust <jugador> | Inicia el flujo de reputación positiva. |
| /status distrust <jugador> | Inicia reputación negativa y comentario. |
| /status comments <jugador> | Abre comentarios e historial público. |
| /status vouch <jugador> | Inicia un respaldo con garantía. |
| /status psychosis [jugador] | Consulta información de Killing Psychosis. |
| /status admin ... | Operaciones administrativas auditadas. |

15.2 Configuración sugerida

Rangos de status y mapping a prefixes existentes.

Colores de chat por rango.

Porcentajes de coste, coste mínimo y multiplicadores progresivos.

Cooldown por pareja y límites por ventana temporal.

Umbrales de Confidence.

Decay y vida útil del peso reputacional.

Palabras y patrones del filtro Bobba.

Límites de comentarios.

Reglas de Vouch: stake mínimo, expiración, máximo de vouches y condiciones de pérdida.

Integraciones habilitadas.

15.3 Permisos

Las acciones normales deben estar disponibles para jugadores de forma simple. Las acciones administrativas —ajustes manuales, ocultación de comentarios, reversión de eventos, bypass de cooldowns— deben requerir permisos explícitos y generar logs de auditoría.

16. Fases de implementación

| Fase | Alcance |
| Fase 1 — Núcleo | Status, mapping de prefixes, colores de chat, perfiles y persistencia. |
| Fase 2 — Reputación | Trust/Distrust, economía, cooldowns, comments y filtro Bobba. |
| Fase 3 — GUI | Perfil interactivo, confirmaciones, historial y experiencia visual. |
| Fase 4 — Confidence | Métrica de evidencia, jugadores únicos, decay y recalculo. |
| Fase 5 — Vouching | Garantías, expiración, pérdida parcial y visualización. |
| Fase 6 — Integraciones | Trades, CoreProtect, claims, duelos y API/eventos internos. |

La separación por fases permite probar primero si la reputación cambia realmente el comportamiento social antes de añadir mecánicas más complejas.

17. Criterios de aceptación

☐  Un jugador con status bajo puede seguir enviando mensajes y jugando normalmente.

☐  Los colores de chat cambian según el rango sin volver el texto ilegible.

☐  Status, Confidence y Psychosis se almacenan y muestran como conceptos independientes.

☐  Trust y Distrust cobran el coste configurado y no pueden ejecutarse gratis por error.

☐  Distrust requiere motivo y el filtro Bobba se aplica antes de publicar.

☐  Existe cooldown por pareja y registro de cada evento reputacional.

☐  El historial permite saber quién valoró, cuándo y con qué coste, según reglas de privacidad del servidor.

☐  Los perfiles nuevos aparecen como Unknown/Neutral, no como sospechosos.

☐  Los trade warnings informan sin impedir continuar.

☐  Vouching no transfiere reputación directamente y utiliza una garantía separada.

☐  Las acciones administrativas quedan auditadas.

☐  Los valores clave se pueden ajustar por configuración sin recompilar el plugin.

Resumen de diseño

| Resultado esperado PlayerStatus deja de ser únicamente una puntuación y se convierte en una infraestructura social: reputación explicable, confianza basada en evidencia, agresividad PvP separada, consecuencias ligeras, economía anti-spam y mecanismos de respaldo entre jugadores. La regla general debe mantenerse incluso con nuevas features: la reputación puede condicionar la percepción y las precauciones de otros, pero no convertirse en una ventaja injusta ni en una prohibición de participar. |
