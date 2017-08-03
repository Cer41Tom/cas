import {Component, OnInit, Input} from '@angular/core';
import {Messages} from "../../messages";
import {AbstractRegisteredService} from "../../../domain/registered-service";
import {OAuthRegisteredService, OidcRegisteredService} from "../../../domain/oauth-service";

@Component({
  selector: 'app-oauthclient',
  templateUrl: './oauthclient.component.html'
})
export class OauthclientComponent implements OnInit {

  @Input()
  service: OAuthRegisteredService;

  @Input()
  selectOptions;

  @Input()
  type: String;

  showOAuthSecret: boolean;

  constructor(public messages: Messages) { }

  ngOnInit() {
  }

}
