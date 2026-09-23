<script lang="ts">
	import { onMount } from 'svelte';
	import { Check, ChevronRight } from '@lucide/svelte';
	import { get } from '$lib/api';
	import { currentSubscription, pushSupported } from '$lib/push';
	import { installed } from '$lib/pwa.svelte';
	import { can, currentGroup, session } from '$lib/session.svelte';
	import { slide } from '$lib/motion';

	// Чек-лист «Первые шаги»: старосте — наполнить группу, всем — поставить приложение и уведомления.
	let { oncreate }: { oncreate: () => void } = $props();

	interface Progress {
		subjects: number;
		members: number;
		homework: number;
		news: number;
	}
	interface Step {
		id: string;
		title: string;
		text: string;
		done: boolean;
		href?: string;
		run?: () => void;
	}

	const group = $derived(currentGroup() ?? session.me?.groups[0] ?? null);
	const manager = $derived(!!group && can('create_invites', group.id));
	const key = $derived(`gb-steps-hidden:${group?.id ?? 0}`);

	let progress = $state<Progress | null>(null);
	let isInstalled = $state(true);
	let pushOn = $state(true);
	let hidden = $state(true);

	onMount(async () => {
		try {
			hidden = localStorage.getItem(key) === '1';
		} catch {
			hidden = false;
		}
		isInstalled = installed();
		pushOn = !pushSupported() || (await currentSubscription()) !== null;
		if (manager && group) progress = await get<Progress>(`/api/groups/${group.id}/progress`);
	});

	const steps = $derived.by((): Step[] => {
		const out: Step[] = [];
		if (manager && progress) {
			out.push(
				{
					id: 'subjects',
					title: 'Добавьте предметы',
					text: 'Задания и материалы раскладываются по предметам',
					done: progress.subjects > 0,
					href: '/subjects'
				},
				{
					id: 'invite',
					title: 'Пригласите группу',
					text: 'Ссылка в чат или QR-код на паре',
					done: progress.members > 1,
					href: '/settings?tab=invites'
				},
				{
					id: 'homework',
					title: 'Опубликуйте первое задание',
					text: 'Одногруппники увидят его на главной',
					done: progress.homework > 0,
					run: oncreate
				}
			);
		}
		out.push(
			{
				id: 'install',
				title: 'Установите на телефон',
				text: 'Откроется как приложение, без браузера',
				done: isInstalled,
				href: '/install'
			},
			{
				id: 'push',
				title: 'Включите уведомления',
				text: 'Новые задания и напоминания о сроках',
				done: pushOn,
				href: '/profile#notifications'
			}
		);
		return out;
	});

	const doneCount = $derived(steps.filter((s) => s.done).length);
	const visible = $derived(!hidden && (!manager || progress) && doneCount < steps.length);

	function hide() {
		hidden = true;
		try {
			localStorage.setItem(key, '1');
		} catch {
			/* скроется до перезагрузки */
		}
	}
</script>

{#if visible}
	<section class="steps card" out:slide aria-labelledby="steps-title">
		<header>
			<div>
				<h2 id="steps-title">Первые шаги</h2>
				<p class="muted small num">Готово {doneCount} из {steps.length}</p>
			</div>
			<button class="hide" onclick={hide}>Скрыть</button>
		</header>
		<div class="bar" aria-hidden="true">
			<span style:width="{(doneCount / steps.length) * 100}%"></span>
		</div>
		<ol>
			{#each steps as s (s.id)}
				<li class:done={s.done}>
					{#if s.href}
						<a href={s.href}>{@render row(s)}</a>
					{:else}
						<button onclick={s.run}>{@render row(s)}</button>
					{/if}
				</li>
			{/each}
		</ol>
	</section>
{/if}

{#snippet row(s: Step)}
	<span class="check" aria-hidden="true"
		>{#if s.done}<Check size={16} strokeWidth={3} />{/if}</span
	>
	<span class="text">
		<strong>{s.title}</strong>
		<span class="faint small">{s.done ? 'Готово' : s.text}</span>
	</span>
	{#if !s.done}<ChevronRight size={18} />{/if}
{/snippet}

<style>
	.steps {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: 20px;
		margin-bottom: var(--s6);
	}
	header {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: var(--s3);
	}
	.hide {
		border: 0;
		background: none;
		color: var(--text-3);
		font-size: 14px;
		text-decoration: underline;
		text-underline-offset: 3px;
	}
	.bar {
		height: 6px;
		border-radius: 3px;
		background: var(--surface-2);
		overflow: hidden;
	}
	.bar span {
		display: block;
		height: 100%;
		border-radius: 3px;
		background: var(--accent);
		transition: width 400ms var(--ease);
	}
	ol {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	li + li {
		border-top: 1px solid var(--border);
	}
	li a,
	li button {
		display: flex;
		align-items: center;
		gap: 14px;
		width: 100%;
		padding: 12px 0;
		border: 0;
		background: none;
		color: var(--text);
		text-align: left;
	}
	li a:hover {
		text-decoration: none;
	}
	.check {
		flex: none;
		display: grid;
		place-items: center;
		width: 28px;
		height: 28px;
		border-radius: 50%;
		border: 2px solid var(--border-strong);
	}
	.done .check {
		border-color: var(--accent);
		background: var(--accent);
		color: var(--accent-text);
	}
	.text {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 1px;
	}
	.done strong {
		color: var(--text-3);
		font-weight: 550;
	}
</style>
