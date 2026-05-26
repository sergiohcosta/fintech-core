import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CardForm } from './card-form';

describe('CardForm', () => {
  let component: CardForm;
  let fixture: ComponentFixture<CardForm>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CardForm]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CardForm);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
