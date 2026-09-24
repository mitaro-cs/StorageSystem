import type { Component } from 'svelte';
import {
	Antenna,
	Atom,
	Banknote,
	Binary,
	Bot,
	Brain,
	BrainCircuit,
	Braces,
	BriefcaseBusiness,
	Brush,
	Bug,
	Cable,
	Calculator,
	Camera,
	ChartColumn,
	ChartPie,
	ChartSpline,
	CircuitBoard,
	Code,
	Coins,
	Cpu,
	Database,
	Dna,
	DraftingCompass,
	Drama,
	Dumbbell,
	Earth,
	Factory,
	Feather,
	FlaskConical,
	Gamepad2,
	Gavel,
	Globe,
	GraduationCap,
	Handshake,
	HeartPulse,
	Infinity as InfinityIcon,
	Languages,
	Leaf,
	Library,
	Lightbulb,
	Lock,
	Magnet,
	Megaphone,
	MessageSquareText,
	Microchip,
	Microscope,
	Monitor,
	Music,
	Network,
	NotebookPen,
	Orbit,
	Palette,
	PenTool,
	Percent,
	Pi,
	Puzzle,
	Radio,
	ReceiptText,
	Rocket,
	Router,
	Satellite,
	Scale,
	ScrollText,
	Server,
	ShieldCheck,
	Sigma,
	Signal,
	Smartphone,
	SquareFunction,
	Target,
	Telescope,
	Terminal,
	TestTubeDiagonal,
	Trophy,
	Users,
	Volleyball,
	Wifi,
	Zap,
	BookOpen
} from '@lucide/svelte';

/** Иконка предмета: ключ хранится на сервере (subjects.icon), картинка — здесь. */
export interface SubjectIcon {
	key: string;
	label: string;
	icon: Component<{ size?: number | string; strokeWidth?: number | string }>;
}

export interface IconGroup {
	title: string;
	icons: SubjectIcon[];
}

const i = (key: string, label: string, icon: SubjectIcon['icon']): SubjectIcon => ({
	key,
	label,
	icon
});

/** Набор на выбор в редакторе предмета: сначала то, что бывает в расписании, потом прочее. */
export const ICON_GROUPS: IconGroup[] = [
	{
		title: 'Точные науки',
		icons: [
			i('sigma', 'Математика', Sigma),
			i('function', 'Функции', SquareFunction),
			i('pi', 'Пи', Pi),
			i('infinity', 'Анализ', InfinityIcon),
			i('calculator', 'Вычисления', Calculator),
			i('percent', 'Проценты', Percent),
			i('chart-spline', 'Статистика', ChartSpline),
			i('compass', 'Геометрия', DraftingCompass)
		]
	},
	{
		title: 'Естественные науки',
		icons: [
			i('atom', 'Физика', Atom),
			i('magnet', 'Магнетизм', Magnet),
			i('zap', 'Электротехника', Zap),
			i('flask', 'Химия', FlaskConical),
			i('test-tube', 'Лаборатория', TestTubeDiagonal),
			i('dna', 'Биология', Dna),
			i('microscope', 'Микроскоп', Microscope),
			i('leaf', 'Экология', Leaf),
			i('earth', 'География', Earth),
			i('telescope', 'Астрономия', Telescope),
			i('orbit', 'Орбита', Orbit)
		]
	},
	{
		title: 'ИТ и связь',
		icons: [
			i('code', 'Программирование', Code),
			i('braces', 'Код', Braces),
			i('terminal', 'Терминал', Terminal),
			i('binary', 'Двоичный код', Binary),
			i('database', 'Базы данных', Database),
			i('server', 'Серверы', Server),
			i('cpu', 'Архитектура ЭВМ', Cpu),
			i('microchip', 'Микросхемы', Microchip),
			i('circuit', 'Электроника', CircuitBoard),
			i('network', 'Сети', Network),
			i('router', 'Маршрутизация', Router),
			i('wifi', 'Беспроводная связь', Wifi),
			i('antenna', 'Антенны', Antenna),
			i('radio', 'Радио', Radio),
			i('signal', 'Мобильная связь', Signal),
			i('satellite', 'Спутниковая связь', Satellite),
			i('cable', 'Линии связи', Cable),
			i('shield', 'Безопасность', ShieldCheck),
			i('lock', 'Криптография', Lock),
			i('bot', 'Искусственный интеллект', Bot),
			i('brain-circuit', 'Машинное обучение', BrainCircuit),
			i('globe', 'Веб', Globe),
			i('monitor', 'Компьютер', Monitor),
			i('smartphone', 'Мобильная разработка', Smartphone),
			i('bug', 'Тестирование', Bug)
		]
	},
	{
		title: 'Гуманитарные',
		icons: [
			i('languages', 'Иностранный язык', Languages),
			i('feather', 'Русский язык', Feather),
			i('book', 'Литература', BookOpen),
			i('scroll', 'История', ScrollText),
			i('scale', 'Право', Scale),
			i('gavel', 'Суд', Gavel),
			i('brain', 'Психология', Brain),
			i('users', 'Социология', Users),
			i('handshake', 'Деловое общение', Handshake),
			i('message', 'Культура речи', MessageSquareText),
			i('lightbulb', 'Философия', Lightbulb),
			i('drama', 'Культурология', Drama),
			i('library', 'Библиотека', Library)
		]
	},
	{
		title: 'Экономика и управление',
		icons: [
			i('coins', 'Экономика', Coins),
			i('banknote', 'Финансы', Banknote),
			i('receipt', 'Бухучёт', ReceiptText),
			i('briefcase', 'Менеджмент', BriefcaseBusiness),
			i('megaphone', 'Маркетинг', Megaphone),
			i('chart-column', 'Аналитика', ChartColumn),
			i('chart-pie', 'Доли', ChartPie),
			i('factory', 'Производство', Factory)
		]
	},
	{
		title: 'Ещё',
		icons: [
			i('dumbbell', 'Физкультура', Dumbbell),
			i('volleyball', 'Спорт', Volleyball),
			i('heart-pulse', 'БЖД и здоровье', HeartPulse),
			i('pen-tool', 'Черчение', PenTool),
			i('palette', 'Искусство', Palette),
			i('brush', 'Дизайн', Brush),
			i('music', 'Музыка', Music),
			i('camera', 'Фото и видео', Camera),
			i('graduation-cap', 'Учёба', GraduationCap),
			i('notebook', 'Практика', NotebookPen),
			i('rocket', 'Проект', Rocket),
			i('target', 'Цель', Target),
			i('puzzle', 'Задачи', Puzzle),
			i('trophy', 'Олимпиада', Trophy),
			i('gamepad', 'Игры', Gamepad2)
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
