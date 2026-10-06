<script lang="ts">
	// Мини-«записи» в шагах тура (0.7, просьба владельца): маленькая сцена из интерфейса, которая
	// сама проигрывается по кругу – что нажать и что произойдёт. Только CSS, без картинок и видео;
	// при «уменьшить движение» – последний кадр.
	let { kind }: { kind: string } = $props();
</script>

<div class="demo d-{kind}" aria-hidden="true">
	{#if kind === 'today'}
		<div class="row r1"><i class="dot amber"></i><b>Лабораторная №3</b><span>завтра</span></div>
		<div class="row r2">
			<i class="dot green"></i><b>9:30 Матанализ</b><span>А-214</span>
			<em class="bar"><u></u></em>
		</div>
		<div class="row r3">
			<i class="dot blue"></i><b>Перенос пары в пятницу</b><span>новость</span>
		</div>
	{:else if kind === 'done'}
		<div class="row t1">
			<i class="ring"><svg viewBox="0 0 16 16"><path d="M4 8.5l2.6 2.5L12 5.5" /></svg></i>
			<b>Решить задачи 1–5</b><span>пт</span>
		</div>
		<div class="row t2"><i class="ring"></i><b>Эссе по истории</b><span>пн</span></div>
		<i class="cursor"></i>
	{:else if kind === 'schedule'}
		<div class="seg">
			<span>День</span><span>Неделя</span><span>Месяц</span><i class="knob"></i>
		</div>
		<div class="days">
			{#each ['пн', 'вт', 'ср', 'чт', 'пт', 'сб'] as d, i (d)}<span
					>{d}<i style:--k={['#1fa37a', '#4f7df5', '#e0633a', '#1fa37a', '#a35cf0', '#4f7df5'][i]}
					></i></span
				>{/each}
			<i class="pick"></i>
		</div>
		<div class="row now">
			<b>11:20 Физика</b><span>идёт</span>
			<em class="bar"><u></u></em>
		</div>
	{:else if kind === 'subjects'}
		<div class="tiles">
			{#each ['Σ', 'λ', 'Ф', 'EN', '∫', '</>'] as g, i (g)}<span
					style:--c={['#4f7df5', '#a35cf0', '#e0633a', '#1fa37a', '#d9a21b', '#1f9bb8'][i]}
					style:--i={i}>{g}</span
				>{/each}
		</div>
		<div class="tabs"><span class="on">ДЗ</span><span>Файлы</span><span>Новости</span></div>
	{:else if kind === 'search'}
		<div class="field"><span class="typed">матан</span><i class="caret"></i></div>
		<div class="row f1">
			<i class="dot blue"></i><b>Математический анализ</b><span>предмет</span>
		</div>
		<div class="row f2"><i class="dot amber"></i><b>Матрицы, вариант 2</b><span>задание</span></div>
	{:else if kind === 'manage'}
		<div class="row">
			<b>Студенты добавляют задания</b><i class="sw s1"><u></u></i>
		</div>
		<div class="row"><b>Напоминать за день</b><i class="sw s2"><u></u></i></div>
		<div class="chip">Ссылка-приглашение скопирована</div>
	{:else if kind === 'moderate'}
		<div class="report">
			<b>Жалоба на комментарий</b>
			<span>«Ответы к контрольной…»</span>
			<div class="btns"><i class="btn hide">Скрыть</i><i class="btn">Оставить</i></div>
			<i class="stamp">Скрыто</i>
		</div>
	{/if}
</div>

<style>
	.demo {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 6px;
		height: 128px;
		margin: 0 0 var(--s3);
		padding: 10px;
		border-radius: 14px;
		background: var(--surface-2);
		overflow: hidden;
		font-size: 12px;
		--loop: 4.8s;
	}
	.row {
		position: relative;
		display: flex;
		align-items: center;
		gap: 8px;
		min-height: 30px;
		padding: 6px 10px;
		border-radius: 10px;
		background: var(--surface);
		box-shadow: var(--shadow-1);
		overflow: hidden;
	}
	.row b {
		flex: 1;
		min-width: 0;
		font-weight: 650;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.row span {
		color: var(--text-3);
	}
	.dot {
		flex: none;
		width: 8px;
		height: 8px;
		border-radius: 50%;
	}
	.green {
		background: #1fa37a;
	}
	.blue {
		background: #4f7df5;
	}
	.amber {
		background: #d9a21b;
	}
	.bar {
		position: absolute;
		left: 0;
		right: 0;
		bottom: 0;
		height: 3px;
		background: var(--surface-3);
	}
	.bar u {
		display: block;
		height: 100%;
		background: var(--accent);
		transform-origin: left;
		animation: grow var(--loop) linear infinite;
	}

	/* «Сегодня»: карточки въезжают по очереди. */
	.d-today .row {
		animation: slide var(--loop) cubic-bezier(0.2, 0.9, 0.3, 1.2) infinite both;
	}
	.d-today .r2 {
		animation-delay: 150ms;
	}
	.d-today .r3 {
		animation-delay: 300ms;
	}

	/* «Отметьте»: курсор нажимает кружок, задание зачёркивается и уходит вниз. */
	.ring {
		flex: none;
		display: grid;
		place-items: center;
		width: 18px;
		height: 18px;
		border: 2px solid var(--border-strong);
		border-radius: 50%;
	}
	.ring svg {
		width: 12px;
		fill: none;
		stroke: #fff;
		stroke-width: 2.4;
		stroke-linecap: round;
		stroke-linejoin: round;
		stroke-dasharray: 14;
		animation: draw var(--loop) infinite;
	}
	.t1 .ring {
		animation: check var(--loop) infinite;
	}
	.t1 b {
		animation: strike var(--loop) infinite;
	}
	.t1 {
		animation: sink var(--loop) cubic-bezier(0.4, 0, 0.2, 1) infinite;
	}
	.t2 {
		animation: rise-up var(--loop) cubic-bezier(0.4, 0, 0.2, 1) infinite;
	}
	.cursor {
		position: absolute;
		top: 22px;
		left: 22px;
		width: 22px;
		height: 22px;
		border-radius: 50%;
		background: color-mix(in srgb, var(--text) 22%, transparent);
		box-shadow: 0 0 0 2px var(--surface);
		animation: tap var(--loop) infinite;
	}

	/* «Расписание»: переключатель видов и выбор дня, у идущей пары – полоска. */
	.seg {
		position: relative;
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		padding: 3px;
		border-radius: 999px;
		background: var(--surface-3);
		text-align: center;
		font-weight: 600;
	}
	.seg span {
		position: relative;
		z-index: 1;
		padding: 3px 0;
	}
	.knob {
		position: absolute;
		top: 3px;
		bottom: 3px;
		left: 3px;
		width: calc((100% - 6px) / 3);
		border-radius: 999px;
		background: var(--surface);
		box-shadow: var(--shadow-1);
		animation: knob calc(var(--loop) * 1.5) cubic-bezier(0.3, 1.2, 0.5, 1) infinite;
	}
	.days {
		position: relative;
		display: grid;
		grid-template-columns: repeat(6, 1fr);
		gap: 4px;
		text-align: center;
	}
	.days span {
		position: relative;
		z-index: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 3px;
		padding: 4px 0;
		color: var(--text-2);
	}
	.days span i {
		width: 5px;
		height: 5px;
		border-radius: 50%;
		background: var(--k);
	}
	.pick {
		position: absolute;
		top: 0;
		bottom: 0;
		left: 0;
		width: calc((100% - 20px) / 6);
		border-radius: 8px;
		background: var(--surface);
		box-shadow: var(--shadow-1);
		animation: pick calc(var(--loop) * 1.5) cubic-bezier(0.3, 1.2, 0.5, 1) infinite;
	}
	.d-schedule .now {
		box-shadow:
			0 0 0 2px var(--accent),
			var(--shadow-1);
	}

	/* «Предметы»: плитки выскакивают, у одной открываются вкладки. */
	.tiles {
		display: grid;
		grid-template-columns: repeat(6, 1fr);
		gap: 6px;
	}
	.tiles span {
		display: grid;
		place-items: center;
		aspect-ratio: 1;
		border-radius: 10px;
		background: var(--c);
		color: #fff;
		font-weight: 750;
		font-size: 13px;
		animation: pop var(--loop) cubic-bezier(0.2, 0.9, 0.3, 1.4) infinite both;
		animation-delay: calc(var(--i) * 90ms);
	}
	.tiles span:first-child {
		animation:
			pop var(--loop) cubic-bezier(0.2, 0.9, 0.3, 1.4) infinite both,
			glow var(--loop) infinite;
	}
	.tabs {
		display: flex;
		gap: 6px;
		margin-top: auto;
		animation: tabs var(--loop) cubic-bezier(0.2, 0.9, 0.3, 1.2) infinite both;
	}
	.tabs span {
		padding: 5px 12px;
		border-radius: 999px;
		background: var(--surface);
		font-weight: 600;
		box-shadow: var(--shadow-1);
	}
	.tabs .on {
		background: var(--accent);
		color: var(--accent-text);
	}

	/* «Найти»: буквы печатаются, ответы появляются. */
	.field {
		display: flex;
		align-items: center;
		height: 32px;
		padding: 0 12px;
		border-radius: 10px;
		background: var(--surface);
		box-shadow: inset 0 0 0 1.5px var(--accent);
		font-size: 13px;
		font-weight: 600;
	}
	.typed {
		display: inline-block;
		overflow: hidden;
		white-space: nowrap;
		width: 0;
		animation: type var(--loop) steps(5) infinite;
	}
	.caret {
		width: 2px;
		height: 16px;
		margin-left: 1px;
		background: var(--accent);
		animation: blink 0.8s steps(1) infinite;
	}
	.f1,
	.f2 {
		animation: result var(--loop) cubic-bezier(0.2, 0.9, 0.3, 1.2) infinite both;
	}
	.f2 {
		animation-delay: 120ms;
	}

	/* «Управление»: переключатели щёлкают, ссылка копируется. */
	.sw {
		flex: none;
		position: relative;
		width: 30px;
		height: 18px;
		border-radius: 999px;
		background: var(--surface-3);
		animation: sw-bg var(--loop) infinite;
	}
	.sw u {
		position: absolute;
		top: 2px;
		left: 2px;
		width: 14px;
		height: 14px;
		border-radius: 50%;
		background: #fff;
		box-shadow: 0 1px 2px rgb(0 0 0 / 0.25);
		animation: sw-knob var(--loop) cubic-bezier(0.3, 1.4, 0.5, 1) infinite;
	}
	.s2,
	.s2 u {
		animation-delay: 500ms;
	}
	.chip {
		align-self: center;
		margin-top: auto;
		padding: 5px 12px;
		border-radius: 999px;
		background: var(--inverse);
		color: var(--inverse-text);
		font-weight: 600;
		animation: toast var(--loop) cubic-bezier(0.2, 0.9, 0.3, 1.3) infinite both;
	}

	/* «Модерация»: жалобу смотрят и скрывают. */
	.report {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 4px;
		height: 100%;
		padding: 10px 12px;
		border-radius: 10px;
		background: var(--surface);
		box-shadow: var(--shadow-1);
	}
	.report span {
		color: var(--text-3);
		animation: blur var(--loop) infinite;
	}
	.btns {
		display: flex;
		gap: 6px;
		margin-top: auto;
	}
	.btn {
		padding: 4px 12px;
		border-radius: 999px;
		background: var(--surface-2);
		font-style: normal;
		font-weight: 600;
	}
	.btn.hide {
		background: var(--danger);
		color: #fff;
		animation: press var(--loop) infinite;
	}
	.stamp {
		position: absolute;
		top: 10px;
		right: 12px;
		padding: 2px 10px;
		border: 2px solid var(--danger);
		border-radius: 6px;
		color: var(--danger);
		font-style: normal;
		font-weight: 800;
		rotate: -8deg;
		animation: stamp var(--loop) cubic-bezier(0.2, 0.9, 0.3, 1.6) infinite both;
	}

	@keyframes slide {
		0% {
			opacity: 0;
			transform: translateX(-24px);
		}
		12%,
		88% {
			opacity: 1;
			transform: none;
		}
		100% {
			opacity: 0;
		}
	}
	@keyframes grow {
		from {
			transform: scaleX(0.05);
		}
		to {
			transform: scaleX(1);
		}
	}
	@keyframes tap {
		0%,
		15% {
			transform: translate(150px, 50px);
			opacity: 0;
		}
		30% {
			transform: none;
			opacity: 1;
		}
		34% {
			transform: scale(0.75);
		}
		40% {
			transform: none;
			opacity: 1;
		}
		50%,
		100% {
			opacity: 0;
		}
	}
	@keyframes check {
		0%,
		33% {
			background: transparent;
			border-color: var(--border-strong);
		}
		38%,
		100% {
			background: #1fa37a;
			border-color: #1fa37a;
		}
	}
	@keyframes draw {
		0%,
		35% {
			stroke-dashoffset: 14;
		}
		45%,
		100% {
			stroke-dashoffset: 0;
		}
	}
	@keyframes strike {
		0%,
		38% {
			text-decoration: none;
			color: var(--text);
		}
		42%,
		100% {
			text-decoration: line-through;
			color: var(--text-3);
		}
	}
	@keyframes sink {
		0%,
		55% {
			transform: none;
			opacity: 1;
		}
		70%,
		90% {
			transform: translateY(36px);
			opacity: 0.5;
		}
		100% {
			transform: none;
			opacity: 1;
		}
	}
	@keyframes rise-up {
		0%,
		55% {
			transform: none;
		}
		70%,
		90% {
			transform: translateY(-36px);
		}
		100% {
			transform: none;
		}
	}
	@keyframes knob {
		0%,
		25% {
			transform: none;
		}
		33%,
		58% {
			transform: translateX(100%);
		}
		66%,
		92% {
			transform: translateX(200%);
		}
		100% {
			transform: none;
		}
	}
	@keyframes pick {
		0%,
		15% {
			transform: none;
		}
		25%,
		45% {
			transform: translateX(calc(200% + 8px));
		}
		55%,
		80% {
			transform: translateX(calc(400% + 16px));
		}
		100% {
			transform: none;
		}
	}
	@keyframes pop {
		0% {
			transform: scale(0.3);
			opacity: 0;
		}
		12%,
		90% {
			transform: none;
			opacity: 1;
		}
		100% {
			opacity: 0;
		}
	}
	@keyframes glow {
		0%,
		35% {
			box-shadow: none;
		}
		45%,
		90% {
			box-shadow:
				0 0 0 3px var(--surface-2),
				0 0 0 5px var(--accent);
		}
	}
	@keyframes tabs {
		0%,
		40% {
			opacity: 0;
			transform: translateY(12px);
		}
		50%,
		90% {
			opacity: 1;
			transform: none;
		}
		100% {
			opacity: 0;
		}
	}
	@keyframes type {
		0%,
		8% {
			width: 0;
		}
		45%,
		100% {
			width: 5ch;
		}
	}
	@keyframes blink {
		50% {
			opacity: 0;
		}
	}
	@keyframes result {
		0%,
		45% {
			opacity: 0;
			transform: translateY(8px);
		}
		55%,
		92% {
			opacity: 1;
			transform: none;
		}
		100% {
			opacity: 0;
		}
	}
	@keyframes sw-bg {
		0%,
		20% {
			background: var(--surface-3);
		}
		28%,
		90% {
			background: #1fa37a;
		}
	}
	@keyframes sw-knob {
		0%,
		20% {
			transform: none;
		}
		28%,
		90% {
			transform: translateX(12px);
		}
		100% {
			transform: none;
		}
	}
	@keyframes toast {
		0%,
		50% {
			opacity: 0;
			transform: translateY(14px) scale(0.9);
		}
		58%,
		90% {
			opacity: 1;
			transform: none;
		}
		100% {
			opacity: 0;
		}
	}
	@keyframes blur {
		0%,
		45% {
			filter: none;
		}
		55%,
		92% {
			filter: blur(3px);
		}
	}
	@keyframes press {
		0%,
		38% {
			transform: none;
		}
		42% {
			transform: scale(0.88);
		}
		48%,
		100% {
			transform: none;
		}
	}
	@keyframes stamp {
		0%,
		45% {
			opacity: 0;
			transform: scale(2.2);
		}
		52%,
		92% {
			opacity: 1;
			transform: none;
		}
		100% {
			opacity: 0;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.demo *,
		.demo *::before {
			animation-play-state: paused !important;
			animation-delay: -3.5s !important;
		}
	}
</style>
