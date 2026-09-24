/**
 * Иконка предмета: ключ хранится на сервере (subjects.icon), подпись — здесь, а сама картинка — в
 * subjectIconSet.ts (грузится отдельно, см. iconLoader).
 */
export interface SubjectIcon {
	key: string;
	label: string;
}

export interface IconGroup {
	title: string;
	icons: SubjectIcon[];
}

const i = (key: string, label: string): SubjectIcon => ({ key, label });

/** Набор на выбор в редакторе предмета: сначала то, что бывает в расписании, потом прочее. */
export const ICON_GROUPS: IconGroup[] = [
	{
		title: 'Точные науки',
		icons: [
			i('sigma', 'Математика'),
			i('function', 'Функции'),
			i('pi', 'Пи'),
			i('infinity', 'Анализ'),
			i('calculator', 'Вычисления'),
			i('percent', 'Проценты'),
			i('chart-spline', 'Статистика'),
			i('compass', 'Геометрия')
		]
	},
	{
		title: 'Естественные науки',
		icons: [
			i('atom', 'Физика'),
			i('magnet', 'Магнетизм'),
			i('zap', 'Электротехника'),
			i('flask', 'Химия'),
			i('test-tube', 'Лаборатория'),
			i('dna', 'Биология'),
			i('microscope', 'Микроскоп'),
			i('leaf', 'Экология'),
			i('earth', 'География'),
			i('telescope', 'Астрономия'),
			i('orbit', 'Орбита')
		]
	},
	{
		title: 'ИТ и связь',
		icons: [
			i('code', 'Программирование'),
			i('braces', 'Код'),
			i('terminal', 'Терминал'),
			i('binary', 'Двоичный код'),
			i('database', 'Базы данных'),
			i('server', 'Серверы'),
			i('cpu', 'Архитектура ЭВМ'),
			i('microchip', 'Микросхемы'),
			i('circuit', 'Электроника'),
			i('network', 'Сети'),
			i('router', 'Маршрутизация'),
			i('wifi', 'Беспроводная связь'),
			i('antenna', 'Антенны'),
			i('radio', 'Радио'),
			i('signal', 'Мобильная связь'),
			i('satellite', 'Спутниковая связь'),
			i('cable', 'Линии связи'),
			i('shield', 'Безопасность'),
			i('lock', 'Криптография'),
			i('bot', 'Искусственный интеллект'),
			i('brain-circuit', 'Машинное обучение'),
			i('globe', 'Веб'),
			i('monitor', 'Компьютер'),
			i('smartphone', 'Мобильная разработка'),
			i('bug', 'Тестирование')
		]
	},
	{
		title: 'Гуманитарные',
		icons: [
			i('languages', 'Иностранный язык'),
			i('feather', 'Русский язык'),
			i('book', 'Литература'),
			i('scroll', 'История'),
			i('scale', 'Право'),
			i('gavel', 'Суд'),
			i('brain', 'Психология'),
			i('users', 'Социология'),
			i('handshake', 'Деловое общение'),
			i('message', 'Культура речи'),
			i('lightbulb', 'Философия'),
			i('drama', 'Культурология'),
			i('library', 'Библиотека')
		]
	},
	{
		title: 'Экономика и управление',
		icons: [
			i('coins', 'Экономика'),
			i('banknote', 'Финансы'),
			i('receipt', 'Бухучёт'),
			i('briefcase', 'Менеджмент'),
			i('megaphone', 'Маркетинг'),
			i('chart-column', 'Аналитика'),
			i('chart-pie', 'Доли'),
			i('factory', 'Производство')
		]
	},
	{
		title: 'Ещё',
		icons: [
			i('dumbbell', 'Физкультура'),
			i('volleyball', 'Спорт'),
			i('heart-pulse', 'БЖД и здоровье'),
			i('pen-tool', 'Черчение'),
			i('palette', 'Искусство'),
			i('brush', 'Дизайн'),
			i('music', 'Музыка'),
			i('camera', 'Фото и видео'),
			i('graduation-cap', 'Учёба'),
			i('notebook', 'Практика'),
			i('rocket', 'Проект'),
			i('target', 'Цель'),
			i('puzzle', 'Задачи'),
			i('trophy', 'Олимпиада'),
			i('gamepad', 'Игры')
		]
	}
];

const BY_KEY = new Map(ICON_GROUPS.flatMap((g) => g.icons).map((x) => [x.key, x]));

/** Если ничего не подошло: раскрытая книга. */
export const FALLBACK_ICON = BY_KEY.get('book')!;

/**
 * Подбор по названию предмета: первое совпадение сверху вниз. Порядок важен — «Русский язык»
 * должен попасть в «Русский язык», а не в «Иностранный язык», «Теория вероятностей» — в
 * статистику, а не в «Теорию…» вообще. В JS \w и \b не знают кириллицу — поэтому классы [а-я]
 * указаны явно.
 */
const RULES: [RegExp, string][] = [
	[/безопасност[а-яa-z]* жизнедеят|бжд|медицин|здоров/, 'heart-pulse'],
	[/информацион[а-яa-z]* безопас|кибербез|защит[а-яa-z]* информ|безопасност/, 'shield'],
	[/криптограф|шифр/, 'lock'],
	[/искусствен[а-яa-z]* интеллект|нейросет|(^|[^а-я])ии($|[^а-я])/, 'bot'],
	[/машинн[а-яa-z]* обучен|data science|анализ данн/, 'brain-circuit'],
	[/баз[а-яa-z]* данн|sql|субд/, 'database'],
	[/операцион[а-яa-z]* систем|linux|unix|администрир/, 'terminal'],
	[/программ|python|java|c\+\+|c#|разработ|алгоритм|(^|[^а-я])ооп($|[^а-я])/, 'code'],
	[/веб|web|интернет|html/, 'globe'],
	[/мобильн[а-яa-z]* разработ|android|ios/, 'smartphone'],
	[/тестирован/, 'bug'],
	[/архитектур[а-яa-z]* (эвм|компьют|вычисл)|эвм|микропроцессор/, 'cpu'],
	[/схемотех|электроник|микроэлектрон/, 'circuit'],
	[/цифров[а-яa-z]* (устройств|схем|обработ)/, 'microchip'],
	[/маршрутиз/, 'router'],
	[/беспровод|wi-?fi/, 'wifi'],
	[/спутник|космич/, 'satellite'],
	[/мобильн[а-яa-z]* связ|сотов/, 'signal'],
	[/антенн|распростран[а-яa-z]* радиоволн/, 'antenna'],
	[/радио|сигнал/, 'radio'],
	[/лини[а-яa-z]* связ|кабел|оптическ/, 'cable'],
	[/(^|[^а-я])сет(и|ей|ь|ям|ях)($|[^а-я])|телекоммуник|связ/, 'network'],
	[/теори[а-яa-z]* вероятн|статист|эконометр/, 'chart-spline'],
	[/дифференц|интеграл|уравнен|функци/, 'function'],
	[/геометр/, 'compass'],
	[/дискретн/, 'binary'],
	[/математ|матан|алгебр|анализ|вычислит|численн/, 'sigma'],
	[/электротех|электрич|энергет/, 'zap'],
	[/физик|механик|оптик/, 'atom'],
	[/хими/, 'flask'],
	[/биолог|генет/, 'dna'],
	[/эколог|природопольз/, 'leaf'],
	[/географ/, 'earth'],
	[/астроном/, 'telescope'],
	[/русск[а-яa-z]* язык|культур[а-яa-z]* речи/, 'feather'],
	[/английск|иностранн|немецк|французск|китайск|испанск|english|язык/, 'languages'],
	[/литератур/, 'book'],
	[/истори/, 'scroll'],
	[/прав(о|а|ов)|юрис|юрид/, 'scale'],
	[/психолог/, 'brain'],
	[/социолог|политолог/, 'users'],
	[/делов[а-яa-z]* общ|коммуникац|этик/, 'handshake'],
	[/философ/, 'lightbulb'],
	[/культуролог|театр/, 'drama'],
	[/бухгалт|уч[её]т/, 'receipt'],
	[/финанс|банк/, 'banknote'],
	[/маркетинг|реклам/, 'megaphone'],
	[/менеджм|управлен|проектн/, 'briefcase'],
	[/эконом/, 'coins'],
	[/черчен|инженерн[а-яa-z]* граф|графи/, 'pen-tool'],
	[/дизайн/, 'brush'],
	[/искусств|рисован/, 'palette'],
	[/музык/, 'music'],
	[/физ[а-яa-z]* культур|физкульт|спорт|элективн/, 'dumbbell'],
	[/практик|курсов/, 'notebook']
];

/** Иконка по ключу; без ключа — подобранная по названию. */
export function subjectIcon(key: string | null | undefined, name = ''): SubjectIcon {
	return (key && BY_KEY.get(key)) || guessIcon(name);
}

export function guessIcon(name: string): SubjectIcon {
	const n = name.toLowerCase().replaceAll('ё', 'е');
	for (const [re, key] of RULES) if (re.test(n)) return BY_KEY.get(key)!;
	return FALLBACK_ICON;
}

export function iconExists(key: string): boolean {
	return BY_KEY.has(key);
}

export const ALL_ICON_KEYS = [...BY_KEY.keys()];
