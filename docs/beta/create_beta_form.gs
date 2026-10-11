/**
 * Creates the KHOJ beta-access Google Form (one page, 4 questions + email) and a linked response sheet in YOUR Google account.
 * How to run: open https://script.new, delete the sample code, paste this whole file, click Run
 * (choose createKhojBetaForm), approve the permissions, then open "Execution log" for the form links.
 */
function createKhojBetaForm() {
  const form = FormApp.create('KHOJ Beta Access');
  form.setDescription(
    'KHOJ helps families and friends find each other in crowds, even with no mobile signal. It works phone-to-phone over Bluetooth, with no internet.\n\n' +
    'We are opening a small beta for people who will test it with at least one other person. Android phones only for now.\n' +
    'KHOJ is early software and is not a replacement for emergency services. In an emergency call 112.');
  form.setCollectEmail(true);          // we contact people on this email
  form.setLimitOneResponsePerUser(true);
  form.setShowLinkToRespondAgain(false);
  form.setConfirmationMessage(
    'Thank you! You are on the KHOJ beta list. We will email you if you are selected, with a download link and a short guide. ' +
    'Please find one friend or family member to test with. In an emergency, always call 112.');

  form.addTextItem().setTitle('Full name').setRequired(true);

  const who = form.addMultipleChoiceItem().setTitle('Who will you test KHOJ with?').setRequired(true);
  who.setChoices([
    who.createChoice('1 other person'),
    who.createChoice('2 to 5 people'),
    who.createChoice('6 or more people'),
    who.createChoice('I do not have anyone to test with yet')]);
  who.setHelpText('KHOJ needs at least 2 phones to be useful.');

  const why = form.addMultipleChoiceItem().setTitle('What do you want to use KHOJ for?').setRequired(true);
  why.setChoices([
    why.createChoice('Keeping my family together (outings, markets, travel)'),
    why.createChoice('Festivals, pilgrimages and large crowds'),
    why.createChoice('Trekking or places with no network'),
    why.createChoice('Event organizing, volunteering or security'),
    why.createChoice('Looking after elders or children')]);
  why.showOtherOption(true);

  const ok = form.addCheckboxItem().setTitle('Please confirm').setRequired(true);
  ok.setChoices([ok.createChoice(
    'I have an Android phone, I understand KHOJ is beta software and not a replacement for calling 112, and I agree to be contacted about the beta.')]);

  const sheet = SpreadsheetApp.create('KHOJ Beta Responses');
  form.setDestination(FormApp.DestinationType.SPREADSHEET, sheet.getId());

  Logger.log('Share this link with testers: ' + form.getPublishedUrl());
  Logger.log('Edit the form here:           ' + form.getEditUrl());
  Logger.log('Responses sheet:              ' + sheet.getUrl());
}
