<script lang="ts">
	import { onMount } from 'svelte';
	import { Monitor, Smartphone, X } from '@lucide/svelte';
	import { del, get, post, put } from '$lib/api';
	import { siteUrl } from '$lib/copy';
	import { fmtDate } from '$lib/format';
	import {
		currentSubscription,
		disablePush,
		enablePush,
		explainPushTest,
		needsInstallForPush,
		pushSupported,
		resendSubscription,
		type PushReport
	} from '$lib/push';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { NotificationPrefs, NotificationSettings } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Switch from '$lib/ui/Switch.svelte';
	import SectionHead from '$lib/ui/SectionHead.svelte';
	import { Bell } from '@lucide/svelte';

	let s = $state<NotificationSettings | null>(null);
	let subscribed = $state(false);
	let busy = $state(false);
	let testing = $state(false);
	// Окно приложения на компьютере хоста уведомления системы не показывает (нет service worker и
	// Web Push) — их включают в браузере, открыв там тот же сайт.
	const hostWindow = session.me?.hostWindow ?? false;
	const supported = !hostWindow && pushSupported();
	const needsInstall = supported && needsInstallForPush();
	const inBrowser = $derived(
		(siteUrl().startsWith('https://') ? siteUrl() : location.origin) + '/profile#notifications'
	);

	let offlineOnly = $state(false);

	async function load() {
		try {
			s = await get<NotificationSettings>('/api/me/notifications');
			offlineOnly = false;
		} catch {
			offlineOnly = true;
		}
		subscribed = (await currentSubscription()) !== null;
		// Сервер мог удалить подписку после неудачных отправок, а устройство считает, что всё
		// включено: напоминаем её. Со старым ключом сервера — только включить заново.
		if (subscribed && s?.pushEnabled) {
			const r = await resendSubscription(s.publicKey).catch(() => 'ok' as const);
			if (r === 'stale') subscribed = false;
		}
	}
	onMount(load);

	async function save(patch: Partial<NotificationPrefs>) {
		try {
			s = await put<NotificationSettings>('/api/me/notifications', patch);
		} catch (e) {
			toastError(e);
			load();
		}
	}

	async function turnOn() {
		if (!s) return;
		busy = true;
		try {
			await enablePush(s.publicKey);
			subscribed = true;
			await load();
			toast('Уведомления включены на этом устройстве', 'ok');
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}

	async function turnOff() {
		busy = true;
		try {
			await disablePush();
			subscribed = false;
			await load();
		} finally {
			busy = false;
		}
	}

	async function test() {
		testing = true;
		try {
			let r = await post<PushReport>('/api/push/test');
			// Сервер не знает это устройство — напоминаем подписку и пробуем ещё раз.
			if (r.devices === 0 && (await resendSubscription(s?.publicKey)) === 'ok')
				r = await post<PushReport>('/api/push/test');
			const result = explainPushTest(r);
			toast(result.text, result.ok ? 'ok' : 'error');
			if (result.again) {
				await disablePush();
				subscribed = false;
			}
			await load();
		} catch (e) {
			toastError(e);
		} finally {
			testing = false;
		}
	}

	async function removeDevice(id: number) {
		await del(`/api/push/devices/${id}`).catch(toastError);
		await load();
	}

	const time = $derived(
		s
			? `${String(Math.floor(s.prefs.digestAt / 60)).padStart(2, '0')}:${String(s.prefs.digestAt % 60).padStart(2, '0')}`
			: '08:00'
	);
</script>

<section class="card block" id="notifications">
	<SectionHead
		icon={Bell}
		tone="amber"
		title="Уведомления"
		text="Что присылать на телефон: новые задания, напоминания о сроках, новости."
	/>
	{#if offlineOnly}<p class="faint small">
			Настройки уведомлений откроются, когда появится интернет.
		</p>{/if}

	<div class="device">
		<span class="circle">
			{#if hostWindow}<Monitor size={19} />{:else}<Smartphone size={19} />{/if}
		</span>
		<div class="grow">
			{#if hostWindow}
				<strong>На этом компьютере — через браузер</strong>
				<span class="muted small"
					>Окно приложения не показывает уведомления системы. Откройте сайт в Safari, Chrome или
					Яндекс Браузере и включите их там — будут приходить, даже когда окно закрыто.</span
				>
			{:else if !supported}
				<strong>Этот браузер не показывает уведомления</strong>
				<span class="muted small">Откройте сайт в Chrome, Safari, Firefox или Яндекс Браузере</span>
			{:else if needsInstall}
				<strong>Сначала установите приложение</strong>
				<span class="muted small"
					>На iPhone уведомления приходят только в установленном приложении. <a href="/install"
						>Как установить</a
					>, потом откройте groupbase с экрана «Домой».</span
				>
			{:else if s && !s.pushEnabled}
				<strong>Push-уведомления выключены на сервере</strong>
				<span class="muted small">Их может включить администратор</span>
			{:else if subscribed}
				<strong>Включены на этом устройстве</strong>
				<span class="muted small">Новое будет приходить, даже когда сайт закрыт</span>
			{:else}
				<strong>Уведомления на этом устройстве</strong>
				<span class="muted small"
					>Новые задания и напоминания о сроках — даже когда сайт закрыт</span
				>
			{/if}
		</div>
	</div>
	{#if hostWindow}
		<div class="row wrap">
			<Button href={inBrowser} target="_blank">Открыть в браузере</Button>
		</div>
	{:else if supported && !needsInstall && s?.pushEnabled}
		<div class="row wrap">
			{#if subscribed}
				<Button onclick={test} loading={testing}>Проверить</Button>
				<Button variant="ghost" onclick={turnOff} loading={busy}>Выключить</Button>
			{:else}
				<Button variant="primary" onclick={turnOn} loading={busy}>Включить уведомления</Button>
			{/if}
		</div>
	{/if}

	{#if s}
		<p class="faint small">Что присылать на телефон (в колокольчике видно всё):</p>
		<div class="kv prefs">
			<div>
				<span>Новые задания</span>
				<Switch
					label="Новые задания"
					checked={s.prefs.homework}
					onchange={(v) => save({ homework: v })}
				/>
			</div>
			<div>
				<span>Напоминание за сутки до срока</span>
				<Switch
					label="Напоминание за сутки до срока"
					checked={s.prefs.reminders}
					onchange={(v) => save({ reminders: v })}
				/>
			</div>
			<div>
				<label for="news-pref">Новости</label>
				<select
					id="news-pref"
					class="select"
					value={s.prefs.news}
					onchange={(e) => save({ news: e.currentTarget.value as NotificationPrefs['news'] })}
				>
					<option value="all">все</option>
					<option value="urgent">только срочные</option>
					<option value="none">не присылать</option>
				</select>
			</div>
			<div>
				<span>Новые материалы</span>
				<Switch
					label="Новые материалы"
					checked={s.prefs.materials}
					onchange={(v) => save({ materials: v })}
				/>
			</div>
			<div>
				<span>
					Утренняя сводка
					<span class="faint small block">что сдать сегодня и завтра</span>
				</span>
				<span class="row">
					{#if s.prefs.digest}
						<input
							class="input time num"
							type="time"
							value={time}
							aria-label="Время сводки"
							onchange={(e) => {
								const [h, m] = e.currentTarget.value.split(':').map(Number);
								if (!Number.isNaN(h)) save({ digestAt: h * 60 + (m || 0) });
							}}
						/>
					{/if}
					<Switch
						label="Утренняя сводка"
						checked={s.prefs.digest}
						onchange={(v) => save({ digest: v })}
					/>
				</span>
			</div>
		</div>

		{#if s.devices.length}
			<p class="faint small">Устройства с уведомлениями:</p>
			<ul class="devices">
				{#each s.devices as d (d.id)}
					<li>
						<span class="grow"
							>{d.device || 'Устройство'}
							<span class="faint small">· с {fmtDate(d.createdAt)}</span></span
						>
						<button
							class="circle"
							onclick={() => removeDevice(d.id)}
							aria-label="Отключить {d.device || 'устройство'}"><X size={16} /></button
						>
					</li>
				{/each}
			</ul>
		{/if}
	{/if}
</section>

<style>
	section {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		scroll-margin-top: 80px;
	}
	.device {
		display: flex;
		align-items: center;
		gap: 14px;
	}
	.grow {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.prefs > div {
		min-height: 56px;
	}
	.prefs select {
		width: auto;
		min-height: 40px;
	}
	.time {
		width: auto;
		min-height: 40px;
		padding: 6px 12px;
	}
	.block {
		display: block;
	}
	.devices {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.devices li {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 8px 8px 14px;
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.devices li .grow {
		display: block;
	}
	.devices .circle {
		width: 32px;
		height: 32px;
	}
</style>
