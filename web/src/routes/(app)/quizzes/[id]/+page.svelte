<script lang="ts">
	import { onDestroy } from 'svelte';
	import { page } from '$app/state';
	import {
		ChevronLeft,
		ChevronRight,
		CircleCheck,
		ClipboardCheck,
		Clock,
		Pencil,
		Users,
		X
	} from '@lucide/svelte';
	import { get, post } from '$lib/api';
	import { plural } from '$lib/format';
	import { toastError } from '$lib/toasts.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Empty from '$lib/ui/Empty.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import {
		KIND_LABEL,
		scoreText,
		type Answer,
		type Attempt,
		type QuizDetail,
		type QuizResult,
		type ResultRow
	} from '$lib/quiz/types';

	// Тест: вступление → прохождение (таймер, ответы сохраняются на устройстве) → разбор.
	// Ведущим – ещё таблица результатов и «Изменить».
	const id = $derived(Number(page.params.id));

	let detail = $state<QuizDetail | null>(null);
	let missing = $state(false);
	let attempt = $state<Attempt | null>(null);
	let answers = $state<Answer[]>([]);
	let result = $state<QuizResult | null>(null);
	let rows = $state<ResultRow[] | null>(null);
	let busy = $state(false);
	let now = $state(Date.now());
	let editorOpen = $state(false);
	const timer = setInterval(() => (now = Date.now()), 1000);
	onDestroy(() => clearInterval(timer));

	async function load() {
		try {
			detail = await get<QuizDetail>(`/api/quizzes/${id}`);
			missing = false;
			if (detail.quiz.can.edit) rows = await get<ResultRow[]>(`/api/quizzes/${id}/results`);
		} catch {
			missing = true;
		}
	}
	$effect(() => {
		void id;
		attempt = null;
		result = null;
		load();
	});

	// Ответы – на устройстве, пока идёт попытка: обновили страницу или пропала сеть – не теряются.
	const key = (a: number) => `gb-quiz-${a}`;
	function restore(a: Attempt) {
		let saved: Answer[] = [];
		try {
			saved = JSON.parse(localStorage.getItem(key(a.id)) ?? '[]');
		} catch {
			saved = [];
		}
		answers = a.questions.map((_, i) => saved[i] ?? { choices: [], text: '' });
	}
	$effect(() => {
		if (!attempt) return;
		const snapshot = JSON.stringify(answers);
		try {
			localStorage.setItem(key(attempt.id), snapshot);
		} catch {
			/* хранилище недоступно – ответы останутся только на странице */
		}
	});

	async function start() {
		busy = true;
		try {
			const a = await post<Attempt>(`/api/quizzes/${id}/attempts`, {});
			restore(a);
			attempt = a;
			result = null;
			window.scrollTo({ top: 0 });
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}

	const answered = (a: Answer) => (a.choices?.length ?? 0) > 0 || !!a.text?.trim();
	const left = $derived(attempt?.deadline ? Math.max(0, attempt.deadline - now) : null);
	const clock = $derived(
		left == null
			? ''
			: `${Math.floor(left / 60000)}:${String(Math.floor((left % 60000) / 1000)).padStart(2, '0')}`
	);
	// Время вышло – отправляем то, что успели.
	$effect(() => {
		if (attempt && left === 0 && !busy) finish(true);
	});

	async function finish(auto = false) {
		if (!attempt) return;
		const empty = answers.filter((a) => !answered(a)).length;
		if (!auto && empty > 0) {
			const ok = await ask(
				`Без ответа: ${empty} ${plural(empty, ['вопрос', 'вопроса', 'вопросов'])}. Завершить всё равно?`,
				{ title: 'Завершить тест?', ok: 'Завершить' }
			);
			if (!ok) return;
		}
		busy = true;
		try {
			result = await post<QuizResult>(`/api/quiz-attempts/${attempt.id}/finish`, { answers });
			try {
				localStorage.removeItem(key(attempt.id));
			} catch {
				/* ничего */
			}
			attempt = null;
			window.scrollTo({ top: 0 });
			load();
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}

	async function openResult(attemptId: number) {
		try {
			result = await get<QuizResult>(`/api/quiz-attempts/${attemptId}`);
			window.scrollTo({ top: 0 });
		} catch (e) {
			toastError(e);
		}
	}

	function toggle(i: number, j: number, multi: boolean) {
		const a = answers[i];
		const c = a.choices ?? [];
		a.choices = multi ? (c.includes(j) ? c.filter((x) => x !== j) : [...c, j]) : [j];
	}

	const q = $derived(detail?.quiz);
	const canStart = $derived(
		!!q &&
			q.published &&
			(!!q.mine.open ||
				((q.attempts === 0 || q.mine.used < q.attempts) && !(q.closesAt && q.closesAt < now)))
	);
	const fmt = (ms: number) =>
		new Date(ms).toLocaleString('ru-RU', {
			day: 'numeric',
			month: 'long',
			hour: '2-digit',
			minute: '2-digit'
		});
</script>

<svelte:head><title>{q?.title ?? 'Тест'} · Campus</title></svelte:head>

<!-- Своя ссылка «назад», не BackBar: иначе он уходит в общий кусок кода и утяжеляет «Сегодня». -->
<a class="back" href={q ? `/subjects/${q.subjectId}?tab=tests` : '/subjects'}
	><ChevronLeft size={18} /> Тесты предмета</a
>

{#if missing}
	<div class="card"><Empty title="Тест не найден" text="Его удалили или он ещё не открыт." /></div>
{:else if !detail || !q}
	<Skeleton lines={5} />
{:else if attempt}
	<!-- Прохождение -->
	<div class="take-head card">
		<div>
			<h1>{attempt.title}</h1>
			<p class="faint small">
				Отвечено {answers.filter(answered).length} из {attempt.questions.length}
			</p>
		</div>
		{#if clock}<span class="timer num" class:low={left != null && left < 60_000}
				><Clock size={16} /> {clock}</span
			>{/if}
	</div>
	<ol class="questions">
		{#each attempt.questions as x, i (i)}
			<li class="card qcard">
				<p class="qhead faint small">
					Вопрос {i + 1} · {KIND_LABEL[x.kind]} · {x.points}
					{plural(x.points, ['балл', 'балла', 'баллов'])}
				</p>
				<p class="qtext">{x.text}</p>
				{#if x.kind === 'text'}
					<input
						class="input"
						bind:value={answers[i].text}
						maxlength="200"
						aria-label="Ответ на вопрос {i + 1}"
						placeholder="Ваш ответ"
					/>
				{:else}
					<div class="opts" role={x.kind === 'single' ? 'radiogroup' : 'group'}>
						{#each x.options as o, j (j)}
							<button
								type="button"
								class="opt"
								role={x.kind === 'single' ? 'radio' : 'checkbox'}
								aria-checked={answers[i].choices?.includes(j) ?? false}
								class:on={answers[i].choices?.includes(j)}
								class:multi={x.kind === 'multi'}
								onclick={() => toggle(i, j, x.kind === 'multi')}><i></i><span>{o}</span></button
							>
						{/each}
					</div>
				{/if}
			</li>
		{/each}
	</ol>
	<div class="finish">
		<Button variant="primary" loading={busy} onclick={() => finish()}>Завершить и проверить</Button>
	</div>
{:else if result}
	<!-- Разбор -->
	<div class="card score" class:good={result.score / Math.max(result.max, 1) >= 0.6}>
		<p class="faint small">{result.title}</p>
		<strong class="num">{scoreText(result.score, result.max)}</strong>
		<p class="faint small">Завершено {fmt(result.finishedAt)}</p>
		<div class="row-btns">
			<Button onclick={() => (result = null)}>К тесту</Button>
			{#if canStart}<Button variant="primary" onclick={start}>Пройти ещё раз</Button>{/if}
		</div>
	</div>
	<ol class="questions">
		{#each result.questions as x, i (i)}
			{@const m = result.marks[i]}
			{@const a = result.answers[i]}
			<li class="card qcard" class:right={m.right} class:wrong={!m.right}>
				<p class="qhead small">
					{#if m.right}<CircleCheck size={15} /> Верно{:else}<X size={15} /> Неверно{/if}
					· {m.points} из {m.max}
				</p>
				<p class="qtext">{x.text}</p>
				{#if x.kind === 'text'}
					<p>Ваш ответ: <strong>{a?.text?.trim() || '—'}</strong></p>
					{#if m.accepted && !m.right}<p class="faint">Верно: {m.accepted.join(', ')}</p>{/if}
				{:else}
					<ul class="opts review">
						{#each x.options as o, j (j)}
							<li
								class:chosen={a?.choices?.includes(j)}
								class:correct={m.correct?.includes(j)}
								class:multi={x.kind === 'multi'}
							>
								<i></i><span>{o}</span>
							</li>
						{/each}
					</ul>
				{/if}
			</li>
		{/each}
	</ol>
{:else}
	<!-- Вступление -->
	<div class="card intro">
		<span class="ic"><ClipboardCheck size={26} /></span>
		<h1>{q.title}</h1>
		{#if q.description}<p class="desc">{q.description}</p>{/if}
		<ul class="facts faint">
			<li>
				{q.questions}
				{plural(q.questions, ['вопрос', 'вопроса', 'вопросов'])}, {q.points}
				{plural(q.points, ['балл', 'балла', 'баллов'])}
			</li>
			<li>{q.timeLimit ? `${q.timeLimit} мин на попытку` : 'Без ограничения времени'}</li>
			<li>
				{q.attempts === 0
					? 'Попыток – сколько угодно'
					: `Попыток: ${q.attempts}, использовано ${q.mine.used}`}
			</li>
			{#if q.closesAt}<li>Принимается до {fmt(q.closesAt)}</li>{/if}
			{#if !q.published}<li><span class="chip">черновик – группа его не видит</span></li>{/if}
		</ul>
		{#if q.mine.best != null && q.mine.max != null}
			<p class="best">Лучший результат: <strong>{scoreText(q.mine.best, q.mine.max)}</strong></p>
		{/if}
		<div class="row-btns">
			{#if canStart}
				<Button variant="primary" loading={busy} onclick={start}
					><ChevronRight size={16} />
					{q.mine.open ? 'Продолжить' : q.mine.used ? 'Пройти ещё раз' : 'Начать'}</Button
				>
			{/if}
			{#if q.mine.last}<Button onclick={() => openResult(q.mine.last!)}>Мой разбор</Button>{/if}
			{#if q.can.edit}<Button onclick={() => (editorOpen = true)}
					><Pencil size={15} /> Изменить</Button
				>{/if}
		</div>
		{#if q.timeLimit && canStart && !q.mine.open}
			<p class="faint small">Время пойдёт сразу после «Начать».</p>
		{/if}
	</div>

	{#if q.can.edit && rows}
		<section class="card results" aria-label="Результаты">
			<h2><Users size={18} /> Результаты</h2>
			{#if rows.length === 0}
				<p class="faint">Пока никто не проходил.</p>
			{:else}
				<ul>
					{#each rows as r (r.attemptId)}
						<li>
							<span class="who">{r.name}</span>
							{#if r.finishedAt != null && r.score != null && r.max != null}
								<button class="link num" onclick={() => openResult(r.attemptId)}
									>{scoreText(r.score, r.max)}</button
								>
							{:else}
								<span class="faint small">проходит сейчас</span>
							{/if}
						</li>
					{/each}
				</ul>
			{/if}
		</section>
	{/if}
{/if}

{#if editorOpen && detail}
	{#await import('$lib/quiz/QuizEditor.svelte') then m}
		<m.default
			bind:open={editorOpen}
			subjectId={detail.quiz.subjectId}
			edit={detail}
			onsaved={(d) => (detail = d)}
		/>
	{/await}
{/if}

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		margin-bottom: var(--s3);
		color: var(--text-2);
		font-weight: 600;
	}
	.back:hover {
		color: var(--text);
		text-decoration: none;
	}
	h1 {
		margin: 0;
		font-size: 24px;
		overflow-wrap: anywhere;
	}
	.intro {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: var(--s3);
		padding: var(--s5);
	}
	.ic {
		display: grid;
		place-items: center;
		width: 52px;
		height: 52px;
		border-radius: 16px;
		background: var(--surface-2);
	}
	.desc {
		margin: 0;
		white-space: pre-wrap;
	}
	.facts {
		margin: 0;
		padding-left: 18px;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.best {
		margin: 0;
	}
	.row-btns {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	.results {
		margin-top: var(--s4);
		padding: var(--s4) var(--s5);
	}
	.results h2 {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0 0 var(--s3);
		font-size: 17px;
	}
	.results ul {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.results li {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		padding: 10px 0;
	}
	.results li + li {
		border-top: 1px solid var(--border);
	}
	.who {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.link {
		border: 0;
		background: none;
		color: var(--text);
		font: inherit;
		font-weight: 600;
		text-decoration: underline;
		text-underline-offset: 3px;
	}
	.take-head {
		position: sticky;
		top: var(--s2);
		z-index: 3;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s3);
		padding: var(--s3) var(--s4);
	}
	.take-head h1 {
		font-size: 18px;
	}
	.take-head p {
		margin: 2px 0 0;
	}
	.timer {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 6px 12px;
		border-radius: 999px;
		background: var(--surface-2);
		font-weight: 700;
	}
	.timer.low {
		background: var(--danger);
		color: #fff;
	}
	.questions {
		margin: var(--s4) 0 0;
		padding: 0;
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.qcard {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: var(--s4);
	}
	.qcard p {
		margin: 0;
	}
	.qhead {
		display: flex;
		align-items: center;
		gap: 6px;
	}
	.qtext {
		font-size: 16.5px;
		font-weight: 600;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.right .qhead {
		color: var(--ok);
	}
	.wrong .qhead {
		color: var(--danger);
	}
	.opts {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.opt,
	.review li {
		display: flex;
		align-items: center;
		gap: 12px;
		min-height: 48px;
		padding: 10px 14px;
		border: 1px solid var(--border);
		border-radius: 14px;
		background: transparent;
		color: var(--text);
		font: inherit;
		text-align: left;
	}
	.opt span,
	.review li span {
		overflow-wrap: anywhere;
	}
	.opt i,
	.review i {
		flex: none;
		width: 20px;
		height: 20px;
		border: 2px solid var(--border-strong);
		border-radius: 50%;
	}
	.opt.multi i,
	.review .multi i {
		border-radius: 6px;
	}
	.opt.on {
		border-color: var(--text);
		background: var(--surface-2);
	}
	.opt.on i,
	.review .chosen i {
		border-color: var(--text);
		background: var(--text);
		box-shadow: inset 0 0 0 3px var(--surface);
	}
	.review .correct {
		border-color: var(--ok);
		background: color-mix(in srgb, var(--ok) 12%, transparent);
	}
	.review .chosen:not(.correct) {
		border-color: var(--danger);
		background: color-mix(in srgb, var(--danger) 10%, transparent);
	}
	.finish {
		display: flex;
		justify-content: flex-end;
		margin: var(--s4) 0;
	}
	.score {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 6px;
		padding: var(--s5);
	}
	.score p {
		margin: 0;
	}
	.score strong {
		font-size: 30px;
		letter-spacing: -0.02em;
	}
	.score.good strong {
		color: var(--ok);
	}
</style>
