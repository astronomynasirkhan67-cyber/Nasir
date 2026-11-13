package com.ipvio.stream

// Translations part A: add playlist, browse, detail page, home screen
val DictA = """
Playlist hinzufügen¦Add playlist¦Playlist ekle¦Dodaj playlistu¦Dodaj playlistu¦Dodaj plejlistu¦Añadir lista¦Ajouter une playlist¦Aggiungi playlist
Dauert nur ein paar Sekunden.¦Only takes a few seconds.¦Yalnızca birkaç saniye sürer.¦Traje samo nekoliko sekundi.¦Traje samo nekoliko sekundi.¦Traje samo nekoliko sekundi.¦Solo tarda unos segundos.¦Ne prend que quelques secondes.¦Ci vogliono solo pochi secondi.
Aus Zwischenablage einfügen¦Paste from clipboard¦Panodan yapıştır¦Zalijepi iz međuspremnika¦Zalijepi iz međuspremnika¦Nalepi iz privremene memorije¦Pegar del portapapeles¦Coller depuis le presse-papiers¦Incolla dagli appunti
Server (z. B. http://server.tld:8080)¦Server (e.g. http://server.tld:8080)¦Sunucu (ör. http://server.tld:8080)¦Server (npr. http://server.tld:8080)¦Server (npr. http://server.tld:8080)¦Server (npr. http://server.tld:8080)¦Servidor (p. ej. http://server.tld:8080)¦Serveur (p. ex. http://server.tld:8080)¦Server (es. http://server.tld:8080)
Benutzername¦Username¦Kullanıcı adı¦Korisničko ime¦Korisničko ime¦Korisničko ime¦Nombre de usuario¦Nom d'utilisateur¦Nome utente
Passwort¦Password¦Parola¦Lozinka¦Lozinka¦Lozinka¦Contraseña¦Mot de passe¦Password
Passwort verbergen¦Hide password¦Parolayı gizle¦Sakrij lozinku¦Sakrij lozinku¦Sakrij lozinku¦Ocultar contraseña¦Masquer le mot de passe¦Nascondi password
Passwort anzeigen¦Show password¦Parolayı göster¦Prikaži lozinku¦Prikaži lozinku¦Prikaži lozinku¦Mostrar contraseña¦Afficher le mot de passe¦Mostra password
Backup-Server {#0} (optional)¦Backup server {#0} (optional)¦Yedek sunucu {#0} (isteğe bağlı)¦Rezervni server {#0} (opcionalno)¦Rezervni poslužitelj {#0} (neobavezno)¦Rezervni server {#0} (opciono)¦Servidor de respaldo {#0} (opcional)¦Serveur de secours {#0} (facultatif)¦Server di riserva {#0} (facoltativo)
+ Backup-Server¦+ Backup server¦+ Yedek sunucu¦+ Rezervni server¦+ Rezervni poslužitelj¦+ Rezervni server¦+ Servidor de respaldo¦+ Serveur de secours¦+ Server di riserva
Fällt der Server aus, wechselt die App von selbst zu einem Backup-Server. Anbieter nennen sie oft als weitere Server- oder DNS-Adressen; Benutzername und Passwort bleiben gleich.¦If the server goes down, the app switches to a backup server by itself. Providers often list these as extra server or DNS addresses; the username and password stay the same.¦Sunucu çökerse uygulama kendiliğinden bir yedek sunucuya geçer. Sağlayıcılar bunları genellikle ek sunucu veya DNS adresleri olarak verir; kullanıcı adı ve parola aynı kalır.¦Ako server prestane raditi, aplikacija sama prelazi na rezervni server. Pružaoci ih često navode kao dodatne adrese servera ili DNS-a; korisničko ime i lozinka ostaju isti.¦Ako poslužitelj prestane raditi, aplikacija sama prelazi na rezervni poslužitelj. Pružatelji ih često navode kao dodatne adrese poslužitelja ili DNS-a; korisničko ime i lozinka ostaju isti.¦Ako server prestane da radi, aplikacija sama prelazi na rezervni server. Provajderi ih često navode kao dodatne adrese servera ili DNS-a; korisničko ime i lozinka ostaju isti.¦Si el servidor se cae, la app cambia sola a un servidor de respaldo. Los proveedores suelen darlos como direcciones de servidor o DNS adicionales; el usuario y la contraseña no cambian.¦Si le serveur tombe en panne, l'app passe d'elle-même à un serveur de secours. Les fournisseurs les indiquent souvent comme adresses de serveur ou DNS supplémentaires ; le nom d'utilisateur et le mot de passe restent les mêmes.¦Se il server non risponde, l'app passa da sola a un server di riserva. I fornitori li indicano spesso come indirizzi server o DNS aggiuntivi; nome utente e password restano gli stessi.
Backup-Server ungültig: Adresse wie http://server.tld:8080 eingeben.¦Invalid backup server: enter an address like http://server.tld:8080.¦Geçersiz yedek sunucu: http://server.tld:8080 gibi bir adres girin.¦Neispravan rezervni server: unesi adresu kao http://server.tld:8080.¦Neispravan rezervni poslužitelj: unesi adresu poput http://server.tld:8080.¦Neispravan rezervni server: unesi adresu kao http://server.tld:8080.¦Servidor de respaldo no válido: introduce una dirección como http://server.tld:8080.¦Serveur de secours non valide : saisis une adresse comme http://server.tld:8080.¦Server di riserva non valido: inserisci un indirizzo come http://server.tld:8080.
Backup-Server¦Backup servers¦Yedek sunucular¦Rezervni serveri¦Rezervni poslužitelji¦Rezervni serveri¦Servidores de respaldo¦Serveurs de secours¦Server di riserva
Backup-Server: {#0}¦Backup servers: {#0}¦Yedek sunucular: {#0}¦Rezervni serveri: {#0}¦Rezervni poslužitelji: {#0}¦Rezervni serveri: {#0}¦Servidores de respaldo: {#0}¦Serveurs de secours : {#0}¦Server di riserva: {#0}
Backup-Server speichern¦Save backup servers¦Yedek sunucuları kaydet¦Sačuvaj rezervne servere¦Spremi rezervne poslužitelje¦Sačuvaj rezervne servere¦Guardar servidores de respaldo¦Enregistrer les serveurs de secours¦Salva server di riserva
Erreichbar¦Reachable¦Erişilebilir¦Dostupan¦Dostupan¦Dostupan¦Accesible¦Joignable¦Raggiungibile
Nicht erreichbar seit {0}¦Not reachable since {0}¦{0} tarihinden beri erişilemiyor¦Nedostupan od {0}¦Nedostupan od {0}¦Nedostupan od {0}¦No accesible desde {0}¦Injoignable depuis {0}¦Non raggiungibile dal {0}
Nicht erreichbar: {0}¦Not reachable: {0}¦Erişilemiyor: {0}¦Nedostupno: {0}¦Nedostupno: {0}¦Nedostupno: {0}¦No accesible: {0}¦Injoignable : {0}¦Non raggiungibile: {0}
Hauptserver: {0}¦Main server: {0}¦Ana sunucu: {0}¦Glavni server: {0}¦Glavni poslužitelj: {0}¦Glavni server: {0}¦Servidor principal: {0}¦Serveur principal : {0}¦Server principale: {0}
Hinzufügen¦Add¦Ekle¦Dodaj¦Dodaj¦Dodaj¦Añadir¦Ajouter¦Aggiungi
Abbrechen¦Cancel¦İptal¦Otkaži¦Otkaži¦Otkaži¦Cancelar¦Annuler¦Annulla
Alle¦All¦Tümü¦Sve¦Sve¦Sve¦Todos¦Tous¦Tutti
Alle anzeigen¦Show all¦Tümünü göster¦Prikaži sve¦Prikaži sve¦Prikaži sve¦Ver todo¦Tout afficher¦Mostra tutto
Neu hinzugefügt¦Recently added¦Yeni eklenenler¦Nedavno dodano¦Nedavno dodano¦Nedavno dodano¦Añadido recientemente¦Ajouts récents¦Aggiunti di recente
Beliebtheit¦Popularity¦Popülerlik¦Popularnost¦Popularnost¦Popularnost¦Popularidad¦Popularité¦Popolarità
Playlist¦Playlist¦Playlist¦Playlista¦Playlista¦Plejlista¦Lista¦Playlist¦Playlist
vor 1990¦before 1990¦1990 öncesi¦prije 1990.¦prije 1990.¦prije 1990.¦antes de 1990¦avant 1990¦prima del 1990
{#0} Titel¦{#0} titles¦{#0} yapım¦{#0} naslova¦{#0} naslova¦{#0} naslova¦{#0} títulos¦{#0} titres¦{#0} titoli
{0} werden geladen … {#1} / {#2}¦Loading {0} … {#1} / {#2}¦{0} yükleniyor … {#1} / {#2}¦Učitavanje: {0} … {#1} / {#2}¦Učitavanje: {0} … {#1} / {#2}¦Učitavanje: {0} … {#1} / {#2}¦Cargando {0} … {#1} / {#2}¦Chargement : {0} … {#1} / {#2}¦Caricamento: {0} … {#1} / {#2}
{0} laden … {#1}/{#2}¦Loading {0} … {#1}/{#2}¦{0} yükleniyor … {#1}/{#2}¦Učitavanje: {0} … {#1}/{#2}¦Učitavanje: {0} … {#1}/{#2}¦Učitavanje: {0} … {#1}/{#2}¦Cargando {0} … {#1}/{#2}¦Chargement : {0} … {#1}/{#2}¦Caricamento: {0} … {#1}/{#2}
Metadaten¦Metadata¦Meta veriler¦Metapodaci¦Metapodaci¦Metapodaci¦Metadatos¦Métadonnées¦Metadati
Länder¦Countries¦Ülkeler¦Države¦Države¦Države¦Países¦Pays¦Paesi
Zurück = Filter¦Back = Filters¦Geri = Filtreler¦Nazad = Filteri¦Natrag = Filtri¦Nazad = Filteri¦Atrás = Filtros¦Retour = Filtres¦Indietro = Filtri
Sortierung¦Sort¦Sıralama¦Sortiranje¦Sortiranje¦Sortiranje¦Orden¦Tri¦Ordinamento
Rating¦Rating¦Puan¦Ocjena¦Ocjena¦Ocena¦Valoración¦Note¦Voto
Jahr¦Year¦Yıl¦Godina¦Godina¦Godina¦Año¦Année¦Anno
Genre¦Genre¦Tür¦Žanr¦Žanr¦Žanr¦Género¦Genre¦Genere
Land¦Country¦Ülke¦Država¦Država¦Država¦País¦Pays¦Paese
Zurücksetzen¦Reset¦Sıfırla¦Resetiraj¦Resetiraj¦Resetuj¦Restablecer¦Réinitialiser¦Ripristina
Raster¦Grid¦Izgara¦Mreža¦Mreža¦Mreža¦Cuadrícula¦Grille¦Griglia
Kino¦Cinema¦Sinema¦Kino¦Kino¦Kino¦Cine¦Cinéma¦Cinema
Ansicht¦View¦Görünüm¦Prikaz¦Prikaz¦Prikaz¦Vista¦Affichage¦Vista
Keine Treffer – Filter zurücksetzen.¦No results – reset the filters.¦Sonuç yok – filtreleri sıfırlayın.¦Nema rezultata – resetirajte filtere.¦Nema rezultata – resetirajte filtre.¦Nema rezultata – resetujte filtere.¦Sin resultados: restablece los filtros.¦Aucun résultat – réinitialisez les filtres.¦Nessun risultato – ripristina i filtri.
Liste¦List¦Liste¦Lista¦Lista¦Lista¦Lista¦Liste¦Elenco
Besetzung¦Cast¦Oyuncular¦Glumci¦Glumci¦Glumci¦Reparto¦Distribution¦Cast
Regie¦Director¦Yönetmen¦Režija¦Režija¦Režija¦Dirección¦Réalisation¦Regia
Erstellt von¦Created by¦Yaratıcı¦Kreator¦Autor ideje¦Kreator¦Creado por¦Créé par¦Ideato da
Schauspiel¦Acting¦Oyunculuk¦Gluma¦Gluma¦Gluma¦Interpretación¦Interprétation¦Recitazione
Drehbuch¦Writing¦Senaryo¦Scenarij¦Scenarij¦Scenarij¦Guion¦Scénario¦Sceneggiatura
geboren {0}¦born {0}¦doğum {0}¦rođen/a {0}¦rođen/a {0}¦rođen/a {0}¦nacimiento {0}¦né(e) le {0}¦nato/a il {0}
Weniger¦Less¦Daha az¦Manje¦Manje¦Manje¦Menos¦Moins¦Meno
Mehr lesen¦Read more¦Devamını oku¦Pročitaj više¦Pročitaj više¦Pročitaj više¦Leer más¦Lire la suite¦Leggi tutto
Infos konnten nicht geladen werden.¦Could not load info.¦Bilgiler yüklenemedi.¦Informacije nisu učitane.¦Informacije nisu učitane.¦Informacije nisu učitane.¦No se pudo cargar la información.¦Impossible de charger les infos.¦Impossibile caricare le informazioni.
Keine Titel in deiner Playlist¦No titles in your playlist¦Playlistinizde yapım yok¦Nema naslova u tvojoj playlisti¦Nema naslova u tvojoj playlisti¦Nema naslova u tvojoj plejlisti¦No hay títulos en tu lista¦Aucun titre dans ta playlist¦Nessun titolo nella tua playlist
In deiner Playlist¦In your playlist¦Playlistinizde¦U tvojoj playlisti¦U tvojoj playlisti¦U tvojoj plejlisti¦En tu lista¦Dans ta playlist¦Nella tua playlist
{#0} weitere Titel dieser Person sind nicht in deiner Playlist.¦{#0} more titles by this person are not in your playlist.¦Bu kişinin {#0} yapımı daha playlistinizde yok.¦Još {#0} naslova ove osobe nije u tvojoj playlisti.¦Još {#0} naslova ove osobe nije u tvojoj playlisti.¦Još {#0} naslova ove osobe nije u tvojoj plejlisti.¦{#0} títulos más de esta persona no están en tu lista.¦{#0} autres titres de cette personne ne sont pas dans ta playlist.¦Altri {#0} titoli di questa persona non sono nella tua playlist.
Lade …¦Loading …¦Yükleniyor …¦Učitavanje …¦Učitavanje …¦Učitavanje …¦Cargando …¦Chargement …¦Caricamento …
Zuletzt geschaut¦Recently watched¦Son izlenenler¦Nedavno gledano¦Nedavno gledano¦Nedavno gledano¦Visto recientemente¦Vus récemment¦Visti di recente
Meine Liste¦My list¦Listem¦Moja lista¦Moja lista¦Moja lista¦Mi lista¦Ma liste¦La mia lista
In Meiner Liste¦In my list¦Listemde¦U mojoj listi¦U mojoj listi¦U mojoj listi¦En mi lista¦Dans ma liste¦Nella mia lista
Filter¦Filters¦Filtreler¦Filteri¦Filtri¦Filteri¦Filtros¦Filtres¦Filtri
Standard¦Default¦Varsayılan¦Standardno¦Standardno¦Standardno¦Predeterminado¦Par défaut¦Predefinito
Kacheln¦Tiles¦Kutucuklar¦Pločice¦Pločice¦Pločice¦Mosaicos¦Tuiles¦Riquadri
{#0} Kategorien¦{#0} categories¦{#0} kategori¦{#0} kategorija¦{#0} kategorija¦{#0} kategorija¦{#0} categorías¦{#0} catégories¦{#0} categorie
Kategorien¦Categories¦Kategoriler¦Kategorije¦Kategorije¦Kategorije¦Categorías¦Catégories¦Categorie
Sonstige¦Other¦Diğer¦Ostalo¦Ostalo¦Ostalo¦Otros¦Autres¦Altro
Anime¦Anime¦Anime¦Anime¦Anime¦Anime¦Anime¦Anime¦Anime
Animation¦Animation¦Animasyon¦Animacija¦Animacija¦Animacija¦Animación¦Animation¦Animazione
Film¦Movie¦Film¦Film¦Film¦Film¦Película¦Film¦Film
{#0} Staffel(n)¦{#0} season(s)¦{#0} sezon¦{#0} sezona¦{#0} sezona¦{#0} sezona¦{#0} temporada(s)¦{#0} saison(s)¦{#0} stagione/i
{#0} Min¦{#0} min¦{#0} dk¦{#0} min¦{#0} min¦{#0} min¦{#0} min¦{#0} min¦{#0} min
bei {#0} Min¦at {#0} min¦{#0}. dakikada¦na {#0} min¦na {#0} min¦na {#0} min¦en el min {#0}¦à {#0} min¦al minuto {#0}
TMDB antwortet mit Fehler {#0}¦TMDB returned error {#0}¦TMDB {#0} hatası döndürdü¦TMDB je vratio grešku {#0}¦TMDB je vratio grešku {#0}¦TMDB je vratio grešku {#0}¦TMDB respondió con error {#0}¦TMDB a répondu avec l'erreur {#0}¦TMDB ha risposto con errore {#0}
Keine Verbindung zu TMDB ({0})¦No connection to TMDB ({0})¦TMDB bağlantısı yok ({0})¦Nema veze s TMDB-om ({0})¦Nema veze s TMDB-om ({0})¦Nema veze s TMDB-om ({0})¦Sin conexión con TMDB ({0})¦Pas de connexion à TMDB ({0})¦Nessuna connessione a TMDB ({0})
Besetzung nicht lesbar ({0})¦Cast could not be read ({0})¦Oyuncu bilgisi okunamadı ({0})¦Glumci se ne mogu pročitati ({0})¦Glumci se ne mogu pročitati ({0})¦Glumci se ne mogu pročitati ({0})¦No se pudo leer el reparto ({0})¦Distribution illisible ({0})¦Cast non leggibile ({0})
Besetzung nicht verfügbar: {0}¦Cast unavailable: {0}¦Oyuncu bilgisi yok: {0}¦Glumci nisu dostupni: {0}¦Glumci nisu dostupni: {0}¦Glumci nisu dostupni: {0}¦Reparto no disponible: {0}¦Distribution indisponible : {0}¦Cast non disponibile: {0}
Keine App zum Öffnen des Trailers gefunden¦No app found to open the trailer¦Fragmanı açacak uygulama bulunamadı¦Nije pronađena aplikacija za otvaranje trailera¦Nije pronađena aplikacija za otvaranje trailera¦Nije pronađena aplikacija za otvaranje trejlera¦No se encontró ninguna app para abrir el tráiler¦Aucune application pour ouvrir la bande-annonce¦Nessuna app trovata per aprire il trailer
Externer Player¦External player¦Harici oynatıcı¦Vanjski player¦Vanjski player¦Spoljni plejer¦Reproductor externo¦Lecteur externe¦Player esterno
Kein externer Player installiert (z. B. VLC)¦No external player installed (e.g. VLC)¦Harici oynatıcı kurulu değil (ör. VLC)¦Nije instaliran vanjski player (npr. VLC)¦Nije instaliran vanjski player (npr. VLC)¦Nije instaliran spoljni plejer (npr. VLC)¦No hay reproductor externo instalado (p. ej. VLC)¦Aucun lecteur externe installé (p. ex. VLC)¦Nessun player esterno installato (es. VLC)
Keine Beschreibung gefunden.¦No description found.¦Açıklama bulunamadı.¦Opis nije pronađen.¦Opis nije pronađen.¦Opis nije pronađen.¦No se encontró descripción.¦Aucune description trouvée.¦Nessuna descrizione trovata.
Tipp: TMDB-Schlüssel in secrets.properties eintragen, dann erscheinen hier Beschreibung, Cover und Bewertungen.¦Tip: add your TMDB key in secrets.properties to see descriptions, covers and ratings here.¦İpucu: Açıklama, kapak ve puanlar için secrets.properties dosyasına TMDB anahtarını girin.¦Savjet: unesite TMDB ključ u secrets.properties da biste ovdje vidjeli opis, naslovnice i ocjene.¦Savjet: unesite TMDB ključ u secrets.properties da biste ovdje vidjeli opis, naslovnice i ocjene.¦Savjet: unesite TMDB ključ u secrets.properties da biste ovdje vidjeli opis, naslovnice i ocene.¦Consejo: añade tu clave de TMDB en secrets.properties para ver aquí descripciones, carátulas y valoraciones.¦Astuce : ajoutez votre clé TMDB dans secrets.properties pour afficher descriptions, jaquettes et notes.¦Suggerimento: inserisci la chiave TMDB in secrets.properties per vedere descrizioni, copertine e voti.
Fortsetzen {0}¦Resume {0}¦Devam et {0}¦Nastavi {0}¦Nastavi {0}¦Nastavi {0}¦Continuar {0}¦Reprendre {0}¦Riprendi {0}
Von vorn¦From start¦Baştan¦Od početka¦Od početka¦Od početka¦Desde el inicio¦Depuis le début¦Dall'inizio
Abspielen¦Play¦Oynat¦Pusti¦Reproduciraj¦Pusti¦Reproducir¦Lire¦Riproduci
Trailer¦Trailer¦Fragman¦Trailer¦Trailer¦Trailer¦Tráiler¦Bande-annonce¦Trailer
Extern¦External¦Harici¦Vanjski¦Vanjski¦Spoljni¦Externo¦Externe¦Esterno
Automatisch (beste)¦Automatic (best)¦Otomatik (en iyi)¦Automatski (najbolja)¦Automatski (najbolja)¦Automatski (najbolja)¦Automático (mejor)¦Automatique (meilleure)¦Automatico (migliore)
Qualität¦Quality¦Kalite¦Kvalitet¦Kvaliteta¦Kvalitet¦Calidad¦Qualité¦Qualità
Automatisch¦Automatic¦Otomatik¦Automatski¦Automatski¦Automatski¦Automático¦Automatique¦Automatico
Sprache¦Language¦Dil¦Jezik¦Jezik¦Jezik¦Idioma¦Langue¦Lingua
Quelle¦Source¦Kaynak¦Izvor¦Izvor¦Izvor¦Fuente¦Source¦Fonte
gilt für alle Folgen¦applies to all episodes¦tüm bölümler için geçerli¦vrijedi za sve epizode¦vrijedi za sve epizode¦vrijedi za sve epizode¦se aplica a todos los episodios¦valable pour tous les épisodes¦vale per tutti gli episodi
Staffeln¦Seasons¦Sezonlar¦Sezone¦Sezone¦Sezone¦Temporadas¦Saisons¦Stagioni
Staffel {#0}¦Season {#0}¦Sezon {#0}¦Sezona {#0}¦Sezona {#0}¦Sezona {#0}¦Temporada {#0}¦Saison {#0}¦Stagione {#0}
Episoden¦Episodes¦Bölümler¦Epizode¦Epizode¦Epizode¦Episodios¦Épisodes¦Episodi
lade Infos …¦loading info …¦bilgiler yükleniyor …¦učitavanje informacija …¦učitavanje informacija …¦učitavanje informacija …¦cargando información …¦chargement des infos …¦caricamento informazioni …
Links/Rechts = Staffel wechseln¦Left/Right = change season¦Sol/Sağ = sezon değiştir¦Lijevo/Desno = promijeni sezonu¦Lijevo/Desno = promijeni sezonu¦Levo/Desno = promijeni sezonu¦Izquierda/Derecha = cambiar temporada¦Gauche/Droite = changer de saison¦Sinistra/Destra = cambia stagione
Ähnliche Titel in deiner Playlist¦Similar titles in your playlist¦Playlistinizdeki benzer yapımlar¦Slični naslovi u tvojoj playlisti¦Slični naslovi u tvojoj playlisti¦Slični naslovi u tvojoj plejlisti¦Títulos similares en tu lista¦Titres similaires dans ta playlist¦Titoli simili nella tua playlist
Dieses Produkt verwendet die TMDB-API, wird aber nicht von TMDB unterstützt oder zertifiziert.¦This product uses the TMDB API but is not endorsed or certified by TMDB.¦Bu ürün TMDB API'sini kullanır, ancak TMDB tarafından onaylanmamış veya sertifikalandırılmamıştır.¦Ovaj proizvod koristi TMDB API, ali ga TMDB ne podržava niti certificira.¦Ovaj proizvod koristi TMDB API, ali ga TMDB ne podržava niti certificira.¦Ovaj proizvod koristi TMDB API, ali ga TMDB ne podržava niti certificira.¦Este producto utiliza la API de TMDB, pero TMDB no lo respalda ni certifica.¦Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.¦Questo prodotto utilizza l'API di TMDB ma non è approvato né certificato da TMDB.
Raster anpassen¦Adjust grid¦Izgarayı ayarla¦Prilagodi mrežu¦Prilagodi mrežu¦Prilagodi mrežu¦Ajustar cuadrícula¦Ajuster la grille¦Regola griglia
Spalten¦Columns¦Sütunlar¦Stupci¦Stupci¦Kolone¦Columnas¦Colonnes¦Colonne
Zeilen¦Rows¦Satırlar¦Redovi¦Redovi¦Redovi¦Filas¦Lignes¦Righe
Die Ansicht dahinter ändert sich sofort. Zurück schließt.¦The view behind changes immediately. Back closes.¦Arkadaki görünüm hemen değişir. Geri kapatır.¦Prikaz iza mijenja se odmah. Nazad zatvara.¦Prikaz iza mijenja se odmah. Natrag zatvara.¦Prikaz iza menja se odmah. Nazad zatvara.¦La vista de fondo cambia al instante. Atrás cierra.¦L'affichage derrière change immédiatement. Retour ferme.¦La vista dietro cambia subito. Indietro chiude.
Keine EPG-Adresse gefunden¦No EPG address found¦EPG adresi bulunamadı¦EPG adresa nije pronađena¦EPG adresa nije pronađena¦EPG adresa nije pronađena¦No se encontró dirección EPG¦Aucune adresse EPG trouvée¦Nessun indirizzo EPG trovato
Playlist enthält keine Sender-IDs (tvg-id)¦Playlist contains no channel IDs (tvg-id)¦Playlist kanal kimliği içermiyor (tvg-id)¦Playlista ne sadrži ID-ove kanala (tvg-id)¦Playlista ne sadrži ID-ove kanala (tvg-id)¦Plejlista ne sadrži ID-ove kanala (tvg-id)¦La lista no contiene ID de canal (tvg-id)¦La playlist ne contient pas d'ID de chaîne (tvg-id)¦La playlist non contiene ID dei canali (tvg-id)
Keine Programmdaten erhalten ({0})¦No guide data received ({0})¦Program verisi alınamadı ({0})¦Podaci programa nisu primljeni ({0})¦Podaci programa nisu primljeni ({0})¦Podaci programa nisu primljeni ({0})¦No se recibieron datos de programación ({0})¦Aucune donnée de programme reçue ({0})¦Nessun dato di programmazione ricevuto ({0})
Geladen: {#0} Sender mit Programm¦Loaded: {#0} channels with guide data¦Yüklendi: programı olan {#0} kanal¦Učitano: {#0} kanala s programom¦Učitano: {#0} kanala s programom¦Učitano: {#0} kanala s programom¦Cargado: {#0} canales con programación¦Chargé : {#0} chaînes avec programme¦Caricati: {#0} canali con programma
Noch nicht geladen¦Not loaded yet¦Henüz yüklenmedi¦Još nije učitano¦Još nije učitano¦Još nije učitano¦Aún sin cargar¦Pas encore chargé¦Non ancora caricato
Wird geladen …¦Loading …¦Yükleniyor …¦Učitavanje …¦Učitavanje …¦Učitavanje …¦Cargando …¦Chargement …¦Caricamento …
Intro überspringen¦Skip intro¦Girişi atla¦Preskoči uvod¦Preskoči uvod¦Preskoči uvod¦Saltar intro¦Passer l'intro¦Salta intro
Rückblick überspringen¦Skip recap¦Önceki bölümleri atla¦Preskoči sažetak¦Preskoči sažetak¦Preskoči rezime¦Saltar resumen¦Passer le résumé¦Salta riepilogo
Abspann überspringen¦Skip credits¦Jeneriği atla¦Preskoči odjavnu špicu¦Preskoči odjavnu špicu¦Preskoči odjavnu špicu¦Saltar créditos¦Passer le générique¦Salta titoli di coda
Titel-Infos¦Title info¦Yapım bilgileri¦Informacije o naslovima¦Informacije o naslovima¦Informacije o naslovima¦Información de títulos¦Infos sur les titres¦Info sui titoli
Cover, Beschreibungen, Bewertungen¦Covers, descriptions, ratings¦Kapaklar, açıklamalar, puanlar¦Naslovnice, opisi, ocjene¦Naslovnice, opisi, ocjene¦Naslovnice, opisi, ocene¦Carátulas, descripciones, valoraciones¦Jaquettes, descriptions, notes¦Copertine, descrizioni, voti
Schauspieler, Regie, ähnliche Titel¦Actors, directors, similar titles¦Oyuncular, yönetmenler, benzer yapımlar¦Glumci, redatelji, slični naslovi¦Glumci, redatelji, slični naslovi¦Glumci, reditelji, slični naslovi¦Actores, directores, títulos similares¦Acteurs, réalisateurs, titres similaires¦Attori, registi, titoli simili
Playlist-Zwischenspeicher¦Playlist cache¦Playlist önbelleği¦Priručna memorija playliste¦Priručna memorija playliste¦Priručna memorija plejliste¦Caché de listas¦Cache des playlists¦Cache delle playlist
Schneller Start ohne Wartezeit¦Fast start without waiting¦Beklemeden hızlı başlangıç¦Brzi start bez čekanja¦Brzi start bez čekanja¦Brzi start bez čekanja¦Inicio rápido sin esperas¦Démarrage rapide sans attente¦Avvio rapido senza attese
Bilder¦Images¦Görseller¦Slike¦Slike¦Slike¦Imágenes¦Images¦Immagini
Zwischengespeicherte Cover und Hintergründe¦Cached covers and backgrounds¦Önbelleğe alınmış kapaklar ve arka planlar¦Spremljene naslovnice i pozadine¦Spremljene naslovnice i pozadine¦Sačuvane naslovnice i pozadine¦Carátulas y fondos en caché¦Jaquettes et arrière-plans en cache¦Copertine e sfondi in cache
"""
